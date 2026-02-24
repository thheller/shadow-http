(ns shadow.http.server.ring
  (:require
    [ring.websocket.protocols :as ring-protocols])
  (:import
    [java.io InputStream File]
    [java.nio ByteBuffer]
    [shadow.http.server
     HttpHandler HttpContext HttpRequest HttpResponse
     WebSocketContext WebSocketHandler]))

(defn build-request-map
  "Builds a Ring request map from an HttpRequest."
  [^HttpRequest request]
  (let [target (.getTarget request)
        qi (.indexOf target (int \?))
        uri (if (neg? qi) target (subs target 0 qi))
        query-string (when (pos? qi) (subs target (inc qi)))

        ;; Ring requires lowercased header names -> values
        ;; HttpRequest.headers already stores name lowercased -> value
        headers (.getHeaders request)

        ;; extract host header for server-name/server-port
        host (.get headers "host")
        [server-name server-port]
        (when host
          (let [ci (.lastIndexOf ^String host (int \:))]
            (if (neg? ci)
              [host 80]
              [(subs host 0 ci)
               (try (Integer/parseInt (subs host (inc ci)))
                    (catch Exception _ 80))])))

        method (keyword (.toLowerCase (.getMethod request)))]

    (cond-> {:request-method method
             :uri uri
             :headers (into {} headers)
             :protocol (.getHttpVersion request)
             :scheme :http
             :server-name (or server-name "localhost")
             :server-port (or server-port 80)
             :remote-addr ""}
      query-string (assoc :query-string query-string))))

(defn write-ring-response
  "Writes a Ring response map to the HttpResponse."
  [^HttpContext ctx ring-response]
  (let [^HttpResponse response (.respond ctx)
        status (get ring-response :status 200)
        headers (get ring-response :headers)
        body (get ring-response :body)]

    (.setStatus response status)

    ;; set content-type from headers if present
    (when-let [ct (get headers "content-type")]
      (.setContentType response ct))

    ;; set all other headers
    (doseq [[k v] headers]
      (when-not (#{"content-type" "content-length" "transfer-encoding"} k)
        (if (string? v)
          (.setHeader response k v)
          ;; ring allows header values to be vectors of strings
          (throw (ex-info "currently not supporting header vector values" {:header k :value v})))))

    ;; handle content-length if specified
    (when-let [cl (get headers "content-length")]
      (try
        (.setContentLength response (Long/parseLong cl))
        (catch Exception _)))

    ;; write body
    (cond
      (nil? body)
      (.noContent response)

      (string? body)
      (.writeString response ^String body)

      (instance? InputStream body)
      (.writeStream response ^InputStream body)

      (instance? (Class/forName "[B") body)
      (with-open [^java.io.OutputStream out (.body response)]
        (.write out ^bytes body)
        (.flush out))

      (instance? File body)
      (with-open [is (java.io.FileInputStream. ^File body)]
        (.writeStream response is))

      (seq? body)
      (with-open [^java.io.OutputStream out (.body response)]
        (doseq [chunk body]
          (when chunk
            (cond
              (string? chunk)
              (.write out (.getBytes ^String chunk "UTF-8"))
              (instance? (Class/forName "[B") chunk)
              (.write out ^bytes chunk))))
        (.flush out))

      :else
      (do (.setContentType response "text/plain")
          (.writeString response (str body))))))

(deftype RingWebSocketHandler [^WebSocketContext ctx listener]
  WebSocketHandler
  (start [_ ws-ctx]
    (let [next (RingWebSocketHandler. ws-ctx listener)
          next-listener (ring-protocols/on-open listener next)]
      (if (satisfies? ring-protocols/Listener next-listener)
        (RingWebSocketHandler. ws-ctx next-listener)
        next)))

  (onText [this _ payload]
    (ring-protocols/on-message listener this payload)
    this)

  (onBinary [this _ payload]
    (ring-protocols/on-message listener this (ByteBuffer/wrap payload))
    this)

  (onPing [this _ payload]
    (ring-protocols/on-ping listener this (ByteBuffer/wrap payload))
    this)

  (onPong [this _ payload]
    (ring-protocols/on-pong listener this (ByteBuffer/wrap payload))
    this)

  (onClose [this status-code reason]
    (ring-protocols/on-close listener this status-code reason))

  ring-protocols/Socket
  (-open? [this]
    (.isOpen ctx))

  (-send [this message]
    (if (string? message)
      (.sendText ctx message)
      (throw (ex-info "TBD" {})))
    this)

  (-ping [this data]
    (.sendPing ctx (.array ^ByteBuffer data))
    this)
  (-pong [this data]
    (.sendPong ctx (.array ^ByteBuffer data))
    this)

  (-close [this code reason]
    (.sendClose ctx code reason)
    this
    ))

(defn ring-ws-handler
  "Creates a WebSocketHandler from a ring.websocket listener map."
  [listener]
  (RingWebSocketHandler. nil listener))

(deftype RingHandler [server handler-fn]
  HttpHandler
  (addedToServer [this server]
    (RingHandler. server handler-fn))

  (cleanup [this])

  (handle [this ctx request]
    (let [ring-request (build-request-map request)
          ring-response (handler-fn ring-request)]

      (when ring-response
        ;; check for websocket upgrade response
        (if-let [listener (:ring.websocket/listener ring-response)]
          ;; websocket upgrade
          (let [ws-handler (ring-ws-handler listener)]
            (.upgradeToWebSocket ^HttpContext ctx ws-handler))

          ;; normal HTTP response
          (write-ring-response ctx ring-response))))))

(defn handler [handler-fn]
  (RingHandler. nil handler-fn))
