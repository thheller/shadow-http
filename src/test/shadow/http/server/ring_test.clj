(ns shadow.http.server.ring-test
  "runs ring-core middleware and websocket listeners against a real server.
   uses raw sockets for http so nothing a client library would normalize hides bugs."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer (deftest is testing use-fixtures)]
    [ring.core.protocols :as ring-protocols]
    [ring.middleware.content-length :refer (wrap-content-length)]
    [ring.middleware.content-type :refer (wrap-content-type)]
    [ring.middleware.cookies :refer (wrap-cookies)]
    [ring.middleware.file :refer (wrap-file)]
    [ring.middleware.flash :refer (wrap-flash)]
    [ring.middleware.head :refer (wrap-head)]
    [ring.middleware.keyword-params :refer (wrap-keyword-params)]
    [ring.middleware.multipart-params :refer (wrap-multipart-params)]
    [ring.middleware.multipart-params.byte-array :refer (byte-array-store)]
    [ring.middleware.nested-params :refer (wrap-nested-params)]
    [ring.middleware.not-modified :refer (wrap-not-modified)]
    [ring.middleware.params :refer (wrap-params)]
    [ring.middleware.resource :refer (wrap-resource)]
    [ring.middleware.session :refer (wrap-session)]
    [ring.middleware.session.memory :refer (memory-store)]
    [ring.util.request :as req]
    [ring.util.response :as resp]
    [ring.websocket :as ws]
    [ring.websocket.protocols :as ws-protocols]
    [shadow.http.server :as srv])
  (:import
    [java.io BufferedInputStream ByteArrayInputStream ByteArrayOutputStream FilterInputStream InputStream OutputStream]
    [java.net Socket URI]
    [java.net.http HttpClient WebSocket WebSocket$Listener]
    [java.nio ByteBuffer]
    [java.nio.file Files]
    [java.nio.file.attribute FileAttribute]
    [java.util Locale]
    [java.util.concurrent LinkedBlockingQueue TimeUnit]
    [java.util.zip GZIPInputStream GZIPOutputStream]))

;; server setup

(def ^:dynamic *port* nil)

(defonce handler-ref (atom nil))

(defn handle! [handler-fn]
  (reset! handler-ref handler-fn))

(defn with-server [f]
  (let [svc (srv/start {:host "127.0.0.1" :port 0}
              (srv/ring-handler (fn [req] (@handler-ref req))))]
    (try
      (binding [*port* (:port svc)]
        (f))
      (finally
        (srv/stop svc)))))

(use-fixtures :once with-server)

;; raw http client

(defn- read-line* ^String [^InputStream in]
  (let [out (ByteArrayOutputStream.)]
    (loop []
      (let [b (.read in)]
        (case b
          -1 (when (pos? (.size out)) (.toString out "ISO-8859-1"))
          13 (do (.read in) (.toString out "ISO-8859-1"))
          (do (.write out (int b)) (recur)))))))

(defn- read-response [^InputStream in head?]
  (when-let [status-line (read-line* in)]
    (let [headers
          (loop [acc []]
            (let [line (read-line* in)]
              (if (str/blank? line)
                acc
                (let [i (str/index-of line ":")]
                  (recur (conj acc [(str/lower-case (subs line 0 i)) (str/trim (subs line (inc i)))]))))))

          hval
          (fn [n] (some (fn [[k v]] (when (= k n) v)) headers))

          status
          (parse-long (second (str/split status-line #" ")))

          body
          (cond
            (or head? (= 204 status) (= 304 status) (< status 200))
            (byte-array 0)

            (= "chunked" (hval "transfer-encoding"))
            (let [out (ByteArrayOutputStream.)]
              (loop []
                (let [size (Long/parseLong (first (str/split (read-line* in) #";")) 16)]
                  (if (zero? size)
                    (do (read-line* in) ;; no trailers
                        (.toByteArray out))
                    (do (.write out (.readNBytes in (int size)))
                        (read-line* in)
                        (recur))))))

            (hval "content-length")
            (.readNBytes in (Integer/parseInt (hval "content-length")))

            :else
            (.readAllBytes in))]

      {:status status
       :headers headers
       :body body})))

(defn- write-request [^OutputStream out method path {:keys [headers body close?]}]
  (let [^bytes body-bytes (if (string? body) (.getBytes ^String body "UTF-8") body)
        chunked? (some #(= "transfer-encoding" (str/lower-case (first %))) headers)
        head (str method " " path " HTTP/1.1\r\n"
                  "host: localhost:" *port* "\r\n"
                  (apply str (for [[k v] headers] (str k ": " v "\r\n")))
                  (when (and body-bytes (not chunked?))
                    (str "content-length: " (alength body-bytes) "\r\n"))
                  (when close?
                    "connection: close\r\n")
                  "\r\n")]
    (.write out (.getBytes head "ISO-8859-1"))
    (when body-bytes
      (.write out body-bytes))
    (.flush out)))

(defn request
  "sends one request over a fresh connection. headers is a seq of [name value] so repeated
   headers can be sent. returns nil if the server closed without a response."
  ([method path]
   (request method path {}))
  ([method path opts]
   (with-open [s (Socket. "127.0.0.1" (int *port*))]
     (.setSoTimeout s 5000)
     (write-request (.getOutputStream s) method path (assoc opts :close? true))
     (read-response (BufferedInputStream. (.getInputStream s)) (= "HEAD" method)))))

(defn pipelined
  "sends all requests over one keep-alive connection, which only works out if each response
   is framed correctly. each request is [method path opts?]"
  [& reqs]
  (with-open [s (Socket. "127.0.0.1" (int *port*))]
    (.setSoTimeout s 5000)
    (let [out (.getOutputStream s)
          in (BufferedInputStream. (.getInputStream s))]
      (doseq [[method path opts] reqs]
        (write-request out method path opts))
      (into [] (for [[method] reqs] (read-response in (= "HEAD" method)))))))

(defn header [res name]
  (some (fn [[k v]] (when (= k name) v)) (:headers res)))

(defn header-values [res name]
  (into [] (keep (fn [[k v]] (when (= k name) v))) (:headers res)))

(defn gzip-bytes ^bytes [^bytes data]
  (let [out (ByteArrayOutputStream.)]
    (with-open [gz (GZIPOutputStream. out)]
      (.write gz data))
    (.toByteArray out)))

(defn body-bytes ^bytes [res]
  (if (= "gzip" (header res "content-encoding"))
    (.readAllBytes (GZIPInputStream. (ByteArrayInputStream. (:body res))))
    (:body res)))

(defn body-str
  ([res]
   (body-str res "UTF-8"))
  ([res ^String charset]
   (String. (body-bytes res) charset)))

(defn tracking-stream
  "InputStream over the given string that delivers the promise when closed"
  [^String s closed]
  (proxy [FilterInputStream] [(ByteArrayInputStream. (.getBytes s "UTF-8"))]
    (close []
      (deliver closed true))))

(defn ok [_]
  {:status 200 :headers {} :body "ok"})

;; request map

(deftest request-map-test
  (let [captured (promise)]
    (handle! (fn [req] (deliver captured req) (ok req)))
    (let [res (request "GET" "/foo%20bar/baz?a=1&b=%20" {:headers [["X-Custom" "Value"]]})
          req (deref captured 1000 nil)]
      (is (= 200 (:status res)))
      (is (= :get (:request-method req)))
      ;; must not be decoded, wrap-file/wrap-resource decode it themselves
      (is (= "/foo%20bar/baz" (:uri req)))
      (is (= "a=1&b=%20" (:query-string req)))
      (is (= :http (:scheme req)))
      (is (= "HTTP/1.1" (:protocol req)))
      (is (= "localhost" (:server-name req)))
      (is (= *port* (:server-port req)))
      (is (= "127.0.0.1" (:remote-addr req)))
      (is (= "Value" (get-in req [:headers "x-custom"])))
      (is (every? #(= % (str/lower-case %)) (keys (:headers req))))
      (is (not (contains? req :body)))
      (is (= (str "http://localhost:" *port* "/foo%20bar/baz?a=1&b=%20") (req/request-url req))))))

(deftest request-method-locale-test
  ;; "OPTIONS".toLowerCase() in a turkish locale is "optıons"
  (let [captured (promise)
        default (Locale/getDefault)]
    (handle! (fn [req] (deliver captured (:request-method req)) (ok req)))
    (Locale/setDefault (Locale/of "tr" "TR"))
    (try
      (request "OPTIONS" "/")
      (finally
        (Locale/setDefault default)))
    (is (= :options (deref captured 1000 nil)))))

(deftest repeated-request-headers-test
  (let [captured (promise)]
    (handle! (wrap-cookies (fn [req] (deliver captured req) (ok req))))
    (request "GET" "/" {:headers [["Cookie" "a=1"]
                                  ["Accept" "text/html"]
                                  ["Cookie" "b=2"]
                                  ["Accept" "application/json"]]})
    (let [req (deref captured 1000 nil)]
      ;; ring spec: join with , except cookie which uses ;
      (is (= "a=1; b=2" (get-in req [:headers "cookie"])))
      (is (= ["text/html" "application/json"] (str/split (get-in req [:headers "accept"]) #",\s*")))
      (is (= {"a" {:value "1"} "b" {:value "2"}} (:cookies req))))))

;; middleware

(deftest params-test
  (let [captured (atom nil)]
    (handle! (-> (fn [req] (reset! captured req) (ok req))
                 (wrap-keyword-params)
                 (wrap-nested-params)
                 (wrap-params)))

    (testing "query string"
      (request "GET" "/?a=1&b[c]=2&d=%C3%A4")
      (is (= {:a "1" :b {:c "2"} :d "ä"} (:params @captured))))

    (testing "urlencoded body"
      (request "POST" "/?q=1" {:headers [["Content-Type" "application/x-www-form-urlencoded"]]
                               :body "x=hello+world&y=%26"})
      (is (= {:q "1" :x "hello world" :y "&"} (:params @captured))))

    (testing "chunked urlencoded body"
      (request "POST" "/" {:headers [["Content-Type" "application/x-www-form-urlencoded"]
                                     ["Transfer-Encoding" "chunked"]]
                           :body "5\r\nx=abc\r\n3\r\n&y=\r\n1\r\n1\r\n0\r\n\r\n"})
      (is (= {:x "abc" :y "1"} (:params @captured))))))

(deftest multipart-params-test
  (let [captured (atom nil)
        boundary "----shadowboundary"
        body (str "--" boundary "\r\n"
                  "Content-Disposition: form-data; name=\"field\"\r\n\r\n"
                  "value\r\n"
                  "--" boundary "\r\n"
                  "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n"
                  "Content-Type: text/plain\r\n\r\n"
                  "file content äöü\r\n"
                  "--" boundary "--\r\n")]
    (handle! (-> (fn [req] (reset! captured req) (ok req))
                 (wrap-multipart-params {:store (byte-array-store)})))

    (let [res (request "POST" "/upload"
                {:headers [["Content-Type" (str "multipart/form-data; boundary=" boundary)]]
                 :body body})
          {:strs [field file]} (:multipart-params @captured)]
      (is (= 200 (:status res)))
      (is (= "value" field))
      (is (= "test.txt" (:filename file)))
      (is (= "text/plain" (:content-type file)))
      (is (= "file content äöü" (String. ^bytes (:bytes file) "UTF-8"))))))

(deftest set-cookie-test
  (handle! (wrap-cookies
             (fn [_]
               {:status 200
                :headers {}
                :body ""
                :cookies {"a" {:value "1"} "b" {:value "2" :path "/"}}})))
  (let [res (request "GET" "/")]
    (is (= #{"a=1" "b=2; Path=/"} (set (header-values res "set-cookie"))))))

(deftest session-and-flash-test
  (handle! (-> (fn [req]
                 (let [n (inc (get-in req [:session :n] 0))]
                   {:status 200
                    :headers {}
                    :body (str n ":" (:flash req))
                    :session {:n n}
                    :flash (str "after-" n)}))
               (wrap-flash)
               (wrap-session {:store (memory-store)})))

  (let [res1 (request "GET" "/")
        cookie (first (str/split (header res1 "set-cookie") #";"))
        res2 (request "GET" "/" {:headers [["Cookie" cookie]]})]
    (is (str/starts-with? cookie "ring-session="))
    (is (= "1:" (body-str res1)))
    (is (= "2:after-1" (body-str res2)))))

(deftest head-test
  (handle! (wrap-head
             (fn [req]
               {:status 200
                :headers {"Content-Type" "text/plain"
                          "X-Method" (name (:request-method req))}
                :body "hello"})))

  (let [[head get] (pipelined ["HEAD" "/"] ["GET" "/"])]
    (is (= 200 (:status head)))
    (is (= "get" (header head "x-method")))
    (is (= "text/plain" (header head "content-type")))
    (is (= "hello" (body-str get)))))

(deftest not-modified-test
  (let [closed (promise)
        last-modified "Wed, 21 Oct 2015 07:28:00 GMT"]
    (handle! (wrap-not-modified
               (fn [req]
                 {:status 200
                  :headers {"Last-Modified" last-modified
                            "ETag" "\"abc\""
                            "Content-Length" "5"}
                  :body (if (= "/stream" (:uri req))
                          (tracking-stream "hello" closed)
                          "hello")})))

    (let [[r1 r2 r3] (pipelined
                       ["GET" "/stream" {:headers [["If-None-Match" "\"abc\""]]}]
                       ["GET" "/" {:headers [["If-Modified-Since" last-modified]]}]
                       ["GET" "/"])]
      (is (= 304 (:status r1)))
      (is (nil? (header r1 "content-length")))
      (is (deref closed 1000 false))
      (is (= 304 (:status r2)))
      (is (= 200 (:status r3)))
      (is (= "hello" (body-str r3))))))

(deftest file-test
  (let [dir (.toFile (Files/createTempDirectory "shadow-http-ring-test" (make-array FileAttribute 0)))
        file (io/file dir "with space.css")]
    (try
      (spit file "body { color: red; }")
      (handle! (-> (fn [_] {:status 404 :headers {} :body "not found"})
                   (wrap-file (str dir))
                   (wrap-content-type)))

      (let [res (request "GET" "/with%20space.css")]
        (is (= 200 (:status res)))
        (is (= "text/css" (header res "content-type")))
        (is (= "body { color: red; }" (body-str res))))

      (is (= 404 (:status (request "GET" "/missing.css"))))
      (finally
        (.delete file)
        (.delete dir)))))

(deftest resource-from-jar-test
  ;; served from the ring-core jar, so the body is an InputStream with Content-Length set
  (handle! (-> (fn [_] {:status 404 :headers {} :body "not found"})
               (wrap-resource "ring/util")))

  (let [expected (slurp (io/resource "ring/util/response.clj"))
        [r1 r2] (pipelined ["GET" "/response.clj"] ["GET" "/response.clj" {:headers [["Accept-Encoding" "gzip"]]}])]
    (is (= 200 (:status r1)))
    (is (= expected (body-str r1)))
    (is (= 200 (:status r2)))
    (is (= "gzip" (header r2 "content-encoding")))
    (is (= expected (body-str r2)))))

(deftest content-length-charset-test
  (handle! (wrap-content-length
             (fn [req]
               (-> (resp/response (if (= "/large" (:uri req))
                                    (apply str (repeat 1000 "ä"))
                                    "größe"))
                   (resp/content-type "text/plain")
                   (resp/charset "ISO-8859-1")))))

  (let [[small large] (pipelined ["GET" "/"] ["GET" "/large"])]
    (is (= "5" (header small "content-length")))
    (is (= "größe" (body-str small "ISO-8859-1")))
    (is (= "1000" (header large "content-length")))
    (is (= (apply str (repeat 1000 "ä")) (body-str large "ISO-8859-1")))))

;; response bodies

(defrecord CustomBody [text])

(extend-protocol ring-protocols/StreamableResponseBody
  CustomBody
  (write-body-to-stream [body _ ^OutputStream out]
    (.write out (.getBytes (str "custom:" (:text body)) "UTF-8"))
    (.close out)))

(deftest response-body-types-test
  (let [closed (promise)
        bodies
        {"/string" "string"
         "/bytes" (.getBytes "bytes" "UTF-8")
         "/stream" (tracking-stream "stream" closed)
         "/seq" (list "a" 1 nil :b)
         "/lazy-seq" (map identity ["x" (.getBytes "y" "UTF-8") "z"])
         "/nil" nil
         "/custom" (->CustomBody "body")}]

    (handle! (fn [req] {:status 200 :headers {} :body (get bodies (:uri req))}))

    (let [[string bytes stream seq lazy-seq nil-body custom]
          (pipelined
            ["GET" "/string"]
            ["GET" "/bytes"]
            ["GET" "/stream"]
            ["GET" "/seq"]
            ["GET" "/lazy-seq"]
            ["GET" "/nil"]
            ["GET" "/custom"])]

      (is (= "string" (body-str string)))
      (is (= "6" (header string "content-length")))
      (is (= "bytes" (body-str bytes)))
      (is (= "5" (header bytes "content-length")))
      (is (= "stream" (body-str stream)))
      (is (deref closed 1000 false) "InputStream body closed")
      ;; ring writes (str chunk) for each seq element
      (is (= "a1:b" (body-str seq)))
      ;; byte arrays in seqs are a shadow-http extension
      (is (= "xyz" (body-str lazy-seq)))
      (is (= "0" (header nil-body "content-length")))
      (is (= "custom:body" (body-str custom))))))

(deftest response-headers-test
  (handle! (fn [_]
             {:status 201
              :headers {"X-Multi" ["a" "b"]
                        "X-Nil" nil
                        "X-Single" "c"}
              :body "created"}))

  (let [res (request "GET" "/")]
    (is (= 201 (:status res)))
    (is (= ["a" "b"] (header-values res "x-multi")))
    (is (= [] (header-values res "x-nil")))
    (is (= "c" (header res "x-single")))))

(deftest default-status-and-fallthrough-test
  (handle! (fn [req]
             (when (= "/found" (:uri req))
               {:headers {} :body "found"})))
  (let [[found missing] (pipelined ["GET" "/found"] ["GET" "/missing"])]
    (is (= 200 (:status found)))
    (is (= "found" (body-str found)))
    (is (= 404 (:status missing)))))

(deftest bodyless-status-test
  (let [closed (promise)]
    (handle! (fn [req]
               (case (:uri req)
                 "/204" {:status 204 :headers {} :body (tracking-stream "ignored" closed)}
                 "/304" {:status 304 :headers {} :body "ignored"}
                 (ok req))))
    (let [[r204 r304 r200] (pipelined ["GET" "/204"] ["GET" "/304"] ["GET" "/"])]
      (is (= 204 (:status r204)))
      (is (deref closed 1000 false))
      (is (= 304 (:status r304)))
      (is (= "ok" (body-str r200))))))

(deftest auto-compress-test
  (let [text (apply str (repeat 2000 "x"))]
    (handle! (fn [req]
               (case (:uri req)
                 "/precompressed"
                 {:status 200
                  :headers {"Content-Type" "text/plain"
                            "Content-Encoding" "gzip"}
                  :body (ByteArrayInputStream. (gzip-bytes (.getBytes text "UTF-8")))}

                 {:status 200
                  :headers {"Content-Type" "text/plain"}
                  :body text})))

    (let [[plain pre] (pipelined
                        ["GET" "/" {:headers [["Accept-Encoding" "gzip"]]}]
                        ["GET" "/precompressed" {:headers [["Accept-Encoding" "gzip"]]}])]
      (is (= ["gzip"] (header-values plain "content-encoding")))
      (is (= text (body-str plain)))
      ;; must not be compressed again
      (is (= ["gzip"] (header-values pre "content-encoding")))
      (is (= text (body-str pre))))))

(deftest header-injection-test
  (handle! (fn [_]
             {:status 302
              :headers {"Location" "/foo\r\nX-Injected: yes"}
              :body ""}))
  (let [res (request "GET" "/")]
    (is (nil? (header res "x-injected")))))

;; websockets

(defn- buffer->str [^ByteBuffer buf]
  (let [b (byte-array (.remaining buf))]
    (.get buf b)
    (String. b "UTF-8")))

(defn ws-connect
  "connects a java.net.http websocket client, all events it receives go into a queue"
  ([path]
   (ws-connect path nil))
  ([path subprotocols]
   (let [events (LinkedBlockingQueue.)
         text (StringBuilder.)
         listener
         (reify WebSocket$Listener
           (onOpen [_ ws]
             (.request ws 1))
           (onText [_ ws data last]
             (.append text data)
             (when last
               (.put events [:text (str text)])
               (.setLength text 0))
             (.request ws 1)
             nil)
           (onBinary [_ ws data last]
             (.put events [:binary (buffer->str data)])
             (.request ws 1)
             nil)
           (onPing [_ ws data]
             ;; the jdk client replies with a pong on its own
             (.put events [:ping (buffer->str data)])
             (.request ws 1)
             nil)
           (onPong [_ ws data]
             (.put events [:pong (buffer->str data)])
             (.request ws 1)
             nil)
           (onClose [_ ws code reason]
             (.put events [:close code reason])
             nil)
           (onError [_ ws error]
             (.put events [:error error])))

         builder (.newWebSocketBuilder (HttpClient/newHttpClient))]

     (when (seq subprotocols)
       (.subprotocols builder (first subprotocols) (into-array String (rest subprotocols))))

     {:ws (.join (.buildAsync builder (URI. (str "ws://127.0.0.1:" *port* path)) listener))
      :events events})))

(defn poll [^LinkedBlockingQueue queue]
  (.poll queue 5 TimeUnit/SECONDS))

(defn next-event [client]
  (poll (:events client)))

(defn ws-handler [listener]
  (fn [req]
    (if (ws/upgrade-request? req)
      {:ring.websocket/listener listener}
      {:status 400 :headers {} :body "expected websocket upgrade"})))

(deftest websocket-map-listener-test
  (let [server-events (LinkedBlockingQueue.)]
    (handle! (ws-handler
               {:on-open
                (fn [socket]
                  (.put server-events [:open (ws/open? socket)])
                  (ws/send socket "hello"))
                :on-message
                (fn [socket msg]
                  (if (instance? CharSequence msg)
                    (ws/send socket (StringBuilder. (str "echo:" msg)))
                    ;; read-only, so no .array
                    (ws/send socket (.asReadOnlyBuffer (ByteBuffer/wrap (.getBytes (str "echo:" (buffer->str msg)) "UTF-8"))))))
                :on-close
                (fn [socket code reason]
                  (.put server-events [:close code reason (ws/open? socket)]))}))

    (let [{:keys [^WebSocket ws] :as client} (ws-connect "/ws")]
      (is (= [:text "hello"] (next-event client)))
      (.join (.sendText ws "hi" true))
      (is (= [:text "echo:hi"] (next-event client)))
      (.join (.sendBinary ws (ByteBuffer/wrap (.getBytes "bin" "UTF-8")) true))
      (is (= [:binary "echo:bin"] (next-event client)))
      (.join (.sendClose ws 1000 "bye"))
      (is (= :close (first (next-event client))))
      (is (= [:open true] (poll server-events)))
      (is (= [:close 1000 "bye" false] (poll server-events))))))

(deftest websocket-on-open-return-value-test
  ;; ring ignores the return value of on-open, a swap! returning a map must not replace the listener
  (let [state (atom {})]
    (handle! (ws-handler
               {:on-open (fn [socket] (swap! state assoc :socket socket))
                :on-message (fn [socket msg] (ws/send socket (str "echo:" msg)))}))

    (let [{:keys [^WebSocket ws] :as client} (ws-connect "/ws")]
      (.join (.sendText ws "hi" true))
      (is (= [:text "echo:hi"] (next-event client)))
      (.sendClose ws 1000 ""))))

(deftest websocket-default-pong-test
  ;; a listener that doesn't implement PingListener gets the default pong
  (handle! (ws-handler
             (reify ws-protocols/Listener
               (on-open [_ _])
               (on-message [_ socket msg] (ws/send socket msg))
               (on-pong [_ _ _])
               (on-error [_ _ _])
               (on-close [_ _ _ _]))))

  (let [{:keys [^WebSocket ws] :as client} (ws-connect "/ws")]
    (.join (.sendPing ws (ByteBuffer/wrap (.getBytes "ping-data" "UTF-8"))))
    (is (= [:pong "ping-data"] (next-event client)))
    (.join (.sendText ws "still-open" true))
    (is (= [:text "still-open"] (next-event client)))
    (.sendClose ws 1000 "")))

(deftest websocket-ping-test
  (let [server-events (LinkedBlockingQueue.)]
    (handle! (ws-handler
               {:on-message
                (fn [socket _]
                  ;; ring.websocket/ping accepts byte arrays and ByteBuffers
                  (ws/ping socket (.getBytes "bytes" "UTF-8"))
                  (ws/ping socket (-> (ByteBuffer/wrap (.getBytes "xxsliceyy" "UTF-8"))
                                      (.position 2)
                                      (.limit 7)))
                  (ws/pong socket (ByteBuffer/allocateDirect 0)))
                :on-pong
                (fn [_ data]
                  (.put server-events [:pong (buffer->str data)]))}))

    (let [{:keys [^WebSocket ws] :as client} (ws-connect "/ws")]
      (.join (.sendText ws "go" true))
      (is (= [:ping "bytes"] (next-event client)))
      (is (= [:ping "slice"] (next-event client)))
      (is (= [:pong ""] (next-event client)))
      (is (= [:pong "bytes"] (poll server-events)))
      (is (= [:pong "slice"] (poll server-events)))
      (.sendClose ws 1000 ""))))

(deftest websocket-close-test
  (let [server-events (LinkedBlockingQueue.)]
    (handle! (ws-handler
               {:on-message (fn [socket _] (ws/close socket 4000 "custom reason"))
                :on-close (fn [_ code reason] (.put server-events [:close code reason]))}))

    (let [{:keys [^WebSocket ws] :as client} (ws-connect "/ws")]
      (.join (.sendText ws "close please" true))
      (is (= [:close 4000 "custom reason"] (next-event client)))
      (is (= :close (first (poll server-events)))))))

(deftest websocket-async-send-test
  (let [result (promise)]
    (handle! (ws-handler
               {:on-open
                (fn [socket]
                  (ws/send socket "async"
                    (fn [] (deliver result :ok))
                    (fn [e] (deliver result e))))}))

    (let [{:keys [^WebSocket ws] :as client} (ws-connect "/ws")]
      (is (= [:text "async"] (next-event client)))
      (is (= :ok (deref result 1000 nil)))
      (.sendClose ws 1000 ""))))

(deftest websocket-on-error-test
  (let [server-events (LinkedBlockingQueue.)]
    (handle! (ws-handler
               {:on-message (fn [_ _] (throw (ex-info "boom" {})))
                :on-error (fn [_ e] (.put server-events [:error (ex-message e)]))
                :on-close (fn [_ code _] (.put server-events [:close code]))}))

    (let [client (ws-connect "/ws")]
      (.join (.sendText ^WebSocket (:ws client) "explode" true))
      (is (= [:error "boom"] (poll server-events)))
      (is (= [:close 1011] (poll server-events)))
      (is (= [:close 1011 ""] (next-event client))))))

(deftest websocket-on-close-after-failing-on-error-test
  ;; ring guarantees on-close once on-open was called
  (let [closed (promise)]
    (handle! (ws-handler
               {:on-message (fn [_ _] (throw (ex-info "boom" {})))
                :on-error (fn [_ _] (throw (ex-info "on-error failed too" {})))
                :on-close (fn [_ code _] (deliver closed code))}))

    (let [client (ws-connect "/ws")]
      (.join (.sendText ^WebSocket (:ws client) "explode" true))
      (is (= 1011 (deref closed 5000 nil))))))

(deftest websocket-subprotocol-test
  (handle! (fn [req]
             (when (some #{"proto-b"} (ws/request-protocols req))
               {:ring.websocket/listener {}
                :ring.websocket/protocol "proto-b"})))

  (let [{:keys [^WebSocket ws]} (ws-connect "/ws" ["proto-a" "proto-b"])]
    (is (= "proto-b" (.getSubprotocol ws)))
    (.sendClose ws 1000 "")))
