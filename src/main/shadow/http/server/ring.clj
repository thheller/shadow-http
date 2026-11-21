(ns shadow.http.server.ring
  (:require
    [clojure.string :as str]
    [shadow.http.server :as-alias srv]
    [ring.core.protocols :as ring-core]
    ;; extends Listener and PingListener to maps, without it map listeners would only work
    ;; if user code happened to load it
    [ring.websocket]
    [ring.websocket.protocols :as ring-ws])
  (:import
    [java.io Closeable File FileInputStream InputStream OutputStream OutputStreamWriter]
    [java.net InetSocketAddress]
    [java.nio ByteBuffer]
    [java.nio.charset StandardCharsets]
    [java.util Locale Map]
    [shadow.http.server
     Header HttpHandler HttpRequest
     WebSocketConnection WebSocketHandler]))

(defn- request-method [^String method]
  (case method
    "GET" :get
    "POST" :post
    "PUT" :put
    "DELETE" :delete
    "HEAD" :head
    "OPTIONS" :options
    "PATCH" :patch
    ;; with the default locale "OPTIONS" would become :optıons in a turkish locale
    (keyword (.toLowerCase method Locale/ROOT))))

(defn- request-headers [^HttpRequest request]
  ;; HttpRequest already lowercased the names and joined repeated headers with ", "
  (let [^Map headers (.getRequestHeaders request)]
    (cond-> (into {} headers)
      ;; ring wants repeated cookie headers joined with "; " instead
      (and (.hasRepeatedRequestHeaders request) (.containsKey headers "cookie"))
      (assoc "cookie"
        (->> (.getRequestHeadersInOrder request)
             (filter #(= "cookie" (.-name ^Header %)))
             (map #(.-value ^Header %))
             (str/join "; "))))))

(defn- remote-addr [^HttpRequest request]
  ;; ring wants only the IP, toString would be "/127.0.0.1:54321"
  (let [addr (.getRemoteAddress request)]
    (if (instance? InetSocketAddress addr)
      (let [^InetSocketAddress addr addr]
        (if-some [inet (.getAddress addr)]
          (.getHostAddress inet)
          (.getHostString addr)))
      (str addr))))

(defn- parse-host
  "splits a Host header value into [server-name server-port]"
  [^String host default-port]
  ;; the port follows the last :, unless that : is part of an IPv6 literal like [::1]
  (let [ci (.lastIndexOf host (int \:))]
    (if (or (neg? ci) (< ci (.lastIndexOf host (int \]))))
      [host default-port]
      [(subs host 0 ci)
       (or (parse-long (subs host (inc ci))) default-port)])))

(defn build-request-map
  "Builds a Ring request map from an HttpRequest."
  [^HttpRequest request]
  (let [target (.getRequestTarget request)
        qi (.indexOf target (int \?))
        ;; ring wants the path as sent, not decoded like .getRequestPath.
        ;; wrap-file and wrap-resource decode it themselves.
        uri (if (neg? qi) target (subs target 0 qi))
        query-string (when (pos? qi) (subs target (inc qi)))

        headers (request-headers request)

        secure? (.isSecure request)
        default-port (if secure? 443 80)

        [server-name server-port]
        (if-some [host (get headers "host")]
          (parse-host host default-port)
          ["localhost" default-port])]

    (cond-> ^{::request request}
      {:request-method (request-method (.getRequestMethod request))
       :uri uri
       :headers headers
       :protocol (.getRequestVersion request)
       :scheme (if secure? :https :http)
       :server-name server-name
       :server-port server-port
       :remote-addr (remote-addr request)}

      query-string
      (assoc :query-string query-string)

      (.requestHasBody request)
      (assoc :body (.requestBody request))
      )))

;; same as the private ring.core.protocols/response-charset, which ring uses for String and ISeq bodies
(def ^:private re-charset
  #"(?x);(?:.*\s)?(?i:charset)=(?:
      ([!\#$%&'*\-+.0-9A-Z\^_`a-z\|~]+)|  # token
      \"((?:\\\"|[^\"])*)\"               # quoted
    )\s*(?:;|$)")

(defn- response-charset ^String [headers]
  (let [content-type (some (fn [[k v]] (when (.equalsIgnoreCase "content-type" ^String k) v)) headers)]
    (when (string? content-type)
      (when-let [m (re-find re-charset content-type)]
        (or (m 1) (m 2))))))

(defn- string->bytes ^bytes [^String s ^String charset]
  (if charset
    (.getBytes s charset)
    (.getBytes s StandardCharsets/UTF_8)))

(defn- write-seq-body [^HttpRequest request body ^String charset]
  (let [^OutputStream out (.responseBody request)
        writer (OutputStreamWriter. out (or charset "UTF-8"))]
    (doseq [chunk body]
      ;; ring writes (str chunk), byte arrays are a shadow-http extension
      (if (bytes? chunk)
        (do (.flush writer)
            (.write out ^bytes chunk))
        (.write writer (str chunk)))
      ;; ring flushes after every chunk, so lazy seqs can stream
      (.flush writer))
    (.close writer)))

(defn- close-body [body]
  (when (instance? Closeable body)
    (.close ^Closeable body)))

(defn write-ring-response
  "Writes a Ring response map to the HttpResponse."
  [^HttpRequest request ring-response]
  (let [status (get ring-response :status 200)
        headers (get ring-response :headers)
        body (get ring-response :body)]

    (.setResponseStatus request status)

    ;; set all other headers
    (doseq [[k v] headers]
      (cond
        (nil? v)
        nil

        (string? v)
        (.setResponseHeader request k v)

        (sequential? v)
        (doseq [^String s v]
          (.addResponseHeader request k s))

        :else
        (throw (ex-info "invalid header value" {:header k :value v}))))

    ;; write body, same output as ring.core.protocols/StreamableResponseBody but using the
    ;; known length where possible
    (cond
      ;; HEAD and 1xx/204/304 never send a body, but keep status and headers (e.g. Content-Length)
      (or (= "HEAD" (.getRequestMethod request))
          (HttpRequest/isBodylessStatus status))
      (do (close-body body)
          (.skipBody request))

      ;; empty body, still needs content-length: 0 so keep-alive clients know where it ends
      (nil? body)
      (.writeBytes request (byte-array 0))

      (string? body)
      (.writeBytes request (string->bytes body (response-charset headers)))

      (bytes? body)
      (.writeBytes request ^bytes body)

      (instance? InputStream body)
      (with-open [^InputStream in body]
        (.writeStream request in))

      (instance? File body)
      (with-open [in (FileInputStream. ^File body)]
        (.writeStream request in))

      (seq? body)
      (write-seq-body request body (response-charset headers))

      ;; anything else extending StreamableResponseBody
      :else
      (let [out (.responseBody request)]
        (ring-core/write-body-to-stream body ring-response out)
        ;; ring's own implementations close it, others might not. closing twice is fine.
        (.close out)))))

(defn- data->bytes
  "ring allows ByteBuffers and byte arrays for binary messages, ping and pong data"
  ^bytes [data]
  (cond
    (bytes? data)
    data

    ;; .array would ignore position/limit and fails for direct or read-only buffers.
    ;; reading from a duplicate leaves the buffer as the caller had it.
    (instance? ByteBuffer data)
    (let [buf (.duplicate ^ByteBuffer data)
          arr (byte-array (.remaining buf))]
      (.get buf arr)
      arr)

    :else
    (throw (IllegalArgumentException. (str "expected a ByteBuffer or byte array, got: " (type data))))))

(deftype RingWebSocketHandler [^:volatile-mutable ^WebSocketConnection ctx listener ping-listener?]
  WebSocketHandler
  (start [this ws-ctx]
    (set! ctx ws-ctx)
    ;; ring ignores whatever on-open returns
    (ring-ws/on-open listener this)
    this)

  (onText [this payload]
    (ring-ws/on-message listener this payload))

  (onBinary [this payload]
    (ring-ws/on-message listener this (ByteBuffer/wrap payload)))

  (onPing [this payload]
    (if ping-listener?
      (ring-ws/on-ping listener this (ByteBuffer/wrap payload))
      ;; PingListener is optional, ring requires the adapter to answer the ping then
      (.sendPong ctx payload)))

  (onPong [this payload]
    (ring-ws/on-pong listener this (ByteBuffer/wrap payload)))

  (onError [this e]
    (ring-ws/on-error listener this e))

  (onClose [this status-code reason]
    (ring-ws/on-close listener this status-code reason))

  ring-ws/Socket
  (-open? [_]
    (.isOpen ctx))

  (-send [_ message]
    (if (instance? CharSequence message)
      (.sendText ctx (.toString ^CharSequence message))
      (.sendBinary ctx (data->bytes message))))

  (-ping [_ data]
    (.sendPing ctx (data->bytes data)))

  (-pong [_ data]
    (.sendPong ctx (data->bytes data)))

  (-close [_ code reason]
    (.sendClose ctx (int code) ^String reason))

  ring-ws/AsyncSocket
  (-send-async [this message succeed fail]
    ;; writes block, so this sends right away instead of async. keeps the message order and
    ;; the callback contract: succeed once written, fail with the exception otherwise.
    (if-some [e (try
                  (ring-ws/-send this message)
                  nil
                  (catch Throwable e
                    e))]
      (fail e)
      (succeed))))

(defn ring-ws-handler
  "Creates a WebSocketHandler from a ring.websocket listener map."
  [listener]
  (RingWebSocketHandler. nil listener (satisfies? ring-ws/PingListener listener)))

(deftype RingHandler [handler-fn]
  HttpHandler
  (handle [this request]
    (let [ring-request (build-request-map request)
          ring-response (handler-fn ring-request)]

      (when ring-response
        ;; check for websocket upgrade response
        ;; prefer our own impl over ring impl if present
        (if-let [^WebSocketHandler handler (::srv/handler ring-response)]
          (.upgradeToWebSocket request handler (::srv/protocol ring-response))
          (if-let [listener (:ring.websocket/listener ring-response)]
            ;; websocket upgrade
            (let [ws-handler (ring-ws-handler listener)
                  sub-protocol (:ring.websocket/protocol ring-response)]
              (.upgradeToWebSocket request ws-handler sub-protocol))

            ;; normal HTTP response
            (write-ring-response request ring-response)))))))

(defn handler [handler-fn]
  (RingHandler. handler-fn))
