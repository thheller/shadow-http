(ns shadow.http.server
  (:require [shadow.http.server.ring :as ring])
  (:import [java.util List]
           [shadow.http.server ClasspathHandler FileHandler Server]))

(defn ring-handler [handler-fn]
  (ring/handler handler-fn))

(defn classpath-handler [prefix]
  (ClasspathHandler/forPrefix prefix))

(defn file-handler [path watch]
  (FileHandler/forPath path))

(defn start [{:keys [host port] :as config} handlers]
  (let [server (Server.)]
    (.setHandlers server ^List handlers)
    (.start server (or host "0.0.0.0") port)
    {:config config
     :server server
     :port (.getLocalPort (.getSocket server))}))

(defn stop [{:keys [^Server server] :as svc}]
  (.stop server)
  (dissoc svc ::server))