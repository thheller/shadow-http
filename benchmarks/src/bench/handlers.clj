(ns bench.handlers
  (:require
    [clojure.string :as str]
    [org.httpkit.server :as httpkit]
    [ring.core.protocols :as ring-protocols]
    [ring.websocket :as ring-ws])
  (:import
    [java.io IOException InputStream OutputStream]
    [java.nio.charset StandardCharsets]
    [shadow.http.server HttpHandler HttpRequest WebSocketConnection WebSocketHandler]))

(set! *warn-on-reflection* true)

(def ^String plaintext-body "Hello, World!")
(def ^bytes plaintext-bytes (.getBytes plaintext-body StandardCharsets/UTF_8))

(def ^String json-body
  "{\"status\":\"ok\",\"server\":\"benchmark\",\"items\":[1,2,3,4,5],\"message\":\"Hello from Clojure HTTP server benchmark\"}")
(def ^bytes json-bytes (.getBytes json-body StandardCharsets/UTF_8))

(def ^:const sse-pool-size 1000)

;; Pre-encoded SSE frames so the benchmark measures server chunking & flushing rather than string formatting.
(def ^"[[B" sse-event-bytes
  (into-array
    (Class/forName "[B")
    (map (fn [i]
           (.getBytes
             (format "data: {\"seq\":%04d,\"msg\":\"benchmark-sse-event-payload-0123456789\"}\n\n" i)
             StandardCharsets/UTF_8))
         (range sse-pool-size))))

(defn parse-int-param
  "Extracts an integer query parameter from a raw query string (e.g. \"events=500&interval_ms=0\")."
  ^long [^String qs ^String key ^long default-val]
  (if (or (nil? qs) (.isEmpty qs))
    default-val
    (let [prefix (str key "=")]
      (or (some (fn [^String pair]
                  (when (.startsWith pair prefix)
                    (try
                      (Long/parseLong (.substring pair (.length prefix)))
                      (catch NumberFormatException _ nil))))
                (str/split qs #"&"))
          default-val))))

;; ---------------------------------------------------------------------------
;; Ring SSE body (shared identically by shadow-http Ring and Jetty)
;; ---------------------------------------------------------------------------

(deftype SseBody [^long event-count ^long interval-ms]
  ring-protocols/StreamableResponseBody
  (write-body-to-stream [_ _ out]
    (let [^OutputStream out out]
      (try
        (loop [i 0]
          (when (< i event-count)
            (.write out ^bytes (aget sse-event-bytes (int (rem i sse-pool-size))))
            (.flush out)
            (when (pos? interval-ms)
              (Thread/sleep interval-ms))
            (recur (inc i))))
        (catch IOException _
          ;; client disconnected early
          nil)
        (finally
          (try
            (.close out)
            (catch IOException _ nil)))))))

(def ^:private ring-ws-echo-response
  {:ring.websocket/listener
   {:on-message
    (fn [socket message]
      (ring-ws/send socket message))}})

(defn ring-handler
  "Standard Ring handler used identically by shadow-http (Ring) and Jetty."
  [req]
  (case (:uri req)
    "/health"
    {:status 200
     :headers {"content-type" "text/plain"}
     :body "ok"}

    "/plaintext"
    {:status 200
     :headers {"content-type" "text/plain; charset=utf-8"}
     :body plaintext-body}

    "/json"
    {:status 200
     :headers {"content-type" "application/json; charset=utf-8"}
     :body json-body}

    "/echo"
    (let [^InputStream in (:body req)
          body (if in (.readAllBytes in) (byte-array 0))]
      {:status 200
       :headers {"content-type" "application/octet-stream"}
       :body body})

    "/sse"
    (let [qs (:query-string req)
          n (parse-int-param qs "events" 500)
          interval-ms (parse-int-param qs "interval_ms" 0)]
      {:status 200
       :headers {"content-type" "text/event-stream"
                 "cache-control" "no-cache"}
       :body (SseBody. n interval-ms)})

    "/ws"
    (if (ring-ws/upgrade-request? req)
      ring-ws-echo-response
      {:status 400
       :headers {"content-type" "text/plain"}
       :body "expected websocket upgrade"})

    {:status 404
     :headers {"content-type" "text/plain"}
     :body "not found"}))

;; ---------------------------------------------------------------------------
;; http-kit handler (same HTTP endpoints + idiomatic as-channel for SSE & WS)
;; ---------------------------------------------------------------------------

(defn httpkit-handler
  "Ring handler for http-kit using as-channel for SSE streaming and WebSockets."
  [req]
  (case (:uri req)
    "/health"
    {:status 200
     :headers {"content-type" "text/plain"}
     :body "ok"}

    "/plaintext"
    {:status 200
     :headers {"content-type" "text/plain; charset=utf-8"}
     :body plaintext-body}

    "/json"
    {:status 200
     :headers {"content-type" "application/json; charset=utf-8"}
     :body json-body}

    "/echo"
    (let [^InputStream in (:body req)
          body (if in (.readAllBytes in) (byte-array 0))]
      {:status 200
       :headers {"content-type" "application/octet-stream"}
       :body body})

    "/sse"
    (let [qs (:query-string req)
          n (parse-int-param qs "events" 500)
          interval-ms (parse-int-param qs "interval_ms" 0)]
      (httpkit/as-channel req
        {:on-open
         (fn [ch]
           (Thread/startVirtualThread
             (fn []
               (if (pos? n)
                 (let [last-idx (dec n)]
                   (when (httpkit/send! ch
                           {:status 200
                            :headers {"content-type" "text/event-stream"
                                      "cache-control" "no-cache"}
                            :body (aget sse-event-bytes 0)}
                           (zero? last-idx))
                     (loop [i 1]
                       (when (< i n)
                         (when (pos? interval-ms)
                           (Thread/sleep interval-ms))
                         (when (httpkit/send! ch
                                 (aget sse-event-bytes (int (rem i sse-pool-size)))
                                 (= i last-idx))
                           (recur (inc i)))))))
                 (httpkit/send! ch
                   {:status 200
                    :headers {"content-type" "text/event-stream"
                              "cache-control" "no-cache"}
                    :body ""}
                   true)))))}))

    "/ws"
    (if (:websocket? req)
      (httpkit/as-channel req
        {:on-receive
         (fn [ch msg]
           (httpkit/send! ch msg))})
      {:status 400
       :headers {"content-type" "text/plain"}
       :body "expected websocket upgrade"})

    {:status 404
     :headers {"content-type" "text/plain"}
     :body "not found"}))

;; ---------------------------------------------------------------------------
;; shadow-http native handler (direct Java HttpHandler / WebSocketHandler)
;; ---------------------------------------------------------------------------

(deftype NativeWsEcho [^:volatile-mutable ^WebSocketConnection ctx]
  WebSocketHandler
  (start [this new-ctx]
    (set! ctx new-ctx)
    this)
  (onText [_ payload]
    (.sendText ctx payload))
  (onBinary [_ payload]
    (.sendBinary ctx payload))
  (onPing [_ payload]
    (.sendPong ctx payload)))

(defn shadow-native-handler
  "Direct shadow-http HttpHandler without Ring map allocation overhead."
  ^HttpHandler []
  (reify HttpHandler
    (handle [_ req]
      (let [^HttpRequest req req]
        (case (.getRequestPath req)
          "/health"
          (do
            (.setResponseStatus req 200)
            (.setResponseHeader req "content-type" "text/plain")
            (.writeString req "ok"))

          "/plaintext"
          (do
            (.setResponseStatus req 200)
            (.setResponseHeader req "content-type" "text/plain; charset=utf-8")
            (.writeBytes req plaintext-bytes))

          "/json"
          (do
            (.setResponseStatus req 200)
            (.setResponseHeader req "content-type" "application/json; charset=utf-8")
            (.writeBytes req json-bytes))

          "/echo"
          (let [body (if (.requestHasBody req)
                       (with-open [in (.requestBody req)]
                         (.readAllBytes in))
                       (byte-array 0))]
            (.setResponseStatus req 200)
            (.setResponseHeader req "content-type" "application/octet-stream")
            (.writeBytes req body))

          "/sse"
          (let [qs (.getRequestQueryString req)
                n (parse-int-param qs "events" 500)
                interval-ms (parse-int-param qs "interval_ms" 0)]
            (.setResponseStatus req 200)
            (.setResponseHeader req "content-type" "text/event-stream")
            (.setResponseHeader req "cache-control" "no-cache")
            (with-open [out (.responseBody req)]
              (try
                (loop [i 0]
                  (when (< i n)
                    (.write out ^bytes (aget sse-event-bytes (int (rem i sse-pool-size))))
                    (.flush out)
                    (when (pos? interval-ms)
                      (Thread/sleep interval-ms))
                    (recur (inc i))))
                (catch IOException _ nil))))

          "/ws"
          (.upgradeToWebSocket req (NativeWsEcho. nil))

          (do
            (.setResponseStatus req 404)
            (.setResponseHeader req "content-type" "text/plain")
            (.writeString req "not found")))))))
