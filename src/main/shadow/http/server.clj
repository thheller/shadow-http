(ns shadow.http.server
  (:require
    [clojure.core.async :as async]
    [shadow.http.server.ring :as ring])
  (:import
    [java.util List]
    [shadow.http.server ClasspathHandler FileHandler HandlerList HttpHandler Server WebSocketConnection WebSocketHandler]))

(defmacro vthread [& body]
  `(let [res# (async/chan 1)]
     (-> (Thread/ofVirtual)
         (.start (fn* []
                   (try
                     (when-some [body-res# (do ~@body)]
                       (async/>!! res# body-res#))
                     (finally
                       (async/close! res#))))))
     res#))

(defn ring-handler [handler-fn]
  (ring/handler handler-fn))

(defn classpath-handler [prefix]
  (ClasspathHandler/forPrefix prefix))

(defn file-handler [path]
  (FileHandler/forPath path))

(deftype CoreAsyncWebSocketHandler [^WebSocketConnection context write-loop ws-in ws-out ws-loop]
  WebSocketHandler
  (start [this new-context]
    (let [write-loop
          (vthread
            (loop []
              (when-some [msg (async/<!! ws-out)]
                (.sendText new-context msg)
                (recur))))]

      (CoreAsyncWebSocketHandler. new-context write-loop ws-in ws-out ws-loop)))

  (onText [this text]
    (async/>!! ws-in text))

  (onPing [this payload]
    (.sendPong context payload))

  (onClose [this status reason]
    (async/close! ws-in)
    (async/close! ws-out)))

(defn ws-upgrade
  ([ws-in ws-out]
   (ws-upgrade ws-in ws-out nil))
  ([ws-in ws-out ws-loop]
   {::handler (CoreAsyncWebSocketHandler. nil nil ws-in ws-out ws-loop)}))

(defn ssl-context-for-file
  ([path]
   (Server/sslContextForFile path "changeit"))
  ([path password]
   (Server/sslContextForFile path password)))

(defn start [{:keys [host port ssl-context] :as config} handler]
  (let [server (Server.)]
    (cond
      (instance? HttpHandler handler)
      (.setHandler server handler)

      (seq handler)
      (.setHandler server (HandlerList/create ^List handler))

      :else
      (throw (ex-info "invalid handler" {:handler handler})))

    (if ssl-context
      (.startSSL server ssl-context (or host "0.0.0.0") port)
      (.start server (or host "0.0.0.0") port))

    (cond->
      {:config config
       :server server
       :port (.getLocalPort (.getSocket server))}
      ssl-context
      (assoc :ssl true)
      host
      (assoc :host host))))

(defn stop [{:keys [^Server server] :as svc}]
  (.stop server)
  (dissoc svc :server))