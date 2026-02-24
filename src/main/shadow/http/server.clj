(ns shadow.http.server
  (:require [shadow.http.server.ring :as ring]))

(defn ring-handler [handler-fn]
  (ring/handler handler-fn))
