(ns bench.client
  (:require
    [clojure.string :as str])
  (:import
    [bench LoadGen]
    [java.util Map]))

(set! *warn-on-reflection* true)

(def default-tests
  ["http-plaintext"
   "http-json"
   "http-post"
   "sse-stream"
   "ws-echo"
   "ws-echo-4k"])

(defn- java-map->clj [^Map m]
  (into {} (map (fn [[k v]] [(keyword k) v])) m))

(defn- make-bytes ^bytes [^long size]
  (let [arr (byte-array size)]
    (dotimes [i size]
      (aset arr i (byte (+ 97 (rem i 26)))))
    arr))

(defn run-test
  [test-name {:keys [host port connections warmup duration sse-events]}]
  (let [res
        (case test-name
          "http-plaintext"
          (LoadGen/runHttp host (int port) "GET" "/plaintext" nil
            (int connections) (int warmup) (int duration))

          "http-json"
          (LoadGen/runHttp host (int port) "GET" "/json" nil
            (int connections) (int warmup) (int duration))

          "http-post"
          (LoadGen/runHttp host (int port) "POST" "/echo" (make-bytes 1024)
            (int connections) (int warmup) (int duration))

          "sse-stream"
          (LoadGen/runSse host (int port) (str "/sse?events=" sse-events)
            (int sse-events) (int connections) (int warmup) (int duration))

          "ws-echo"
          (LoadGen/runWebSocket host (int port) "/ws"
            64 (int connections) (int warmup) (int duration))

          "ws-echo-4k"
          (LoadGen/runWebSocket host (int port) "/ws"
            4096 (int connections) (int warmup) (int duration))

          (throw (ex-info (str "Unknown test: " test-name) {:test test-name})))]
    (assoc (java-map->clj res) :test test-name)))

(defn- format-us [us]
  (cond
    (< us 1000.0) (format "%.1f us" (double us))
    (< us 1000000.0) (format "%.2f ms" (/ (double us) 1000.0))
    :else (format "%.2f s" (/ (double us) 1000000.0))))

(defn- print-progress [r]
  (binding [*out* *err*]
    (let [unit (case (:kind r)
                 "http" "req/s"
                 "sse"  "events/s"
                 "ws"   "msg/s")
          lat-label (if (= "sse" (:kind r)) "ttfe" "lat")]
      (println (format "  %-16s %,10.0f %-8s  %s p50=%-9s p95=%-9s p99=%-9s  err=%d"
                 (:test r)
                 (double (:opsPerSec r))
                 unit
                 lat-label
                 (format-us (:p50Us r))
                 (format-us (:p95Us r))
                 (format-us (:p99Us r))
                 (long (:errors r))))
      (flush))))

(defn- parse-args [args]
  (loop [[a & more] args
         opts {:host "127.0.0.1"
               :port 18080
               :tests default-tests
               :connections 32
               :warmup 2
               :duration 5
               :sse-events 500
               :edn false}]
    (cond
      (nil? a) opts
      (= a "--host")        (recur (rest more) (assoc opts :host (first more)))
      (= a "--port")        (recur (rest more) (assoc opts :port (parse-long (first more))))
      (= a "--tests")       (recur (rest more) (assoc opts :tests (str/split (first more) #",")))
      (= a "--connections") (recur (rest more) (assoc opts :connections (parse-long (first more))))
      (= a "--warmup")      (recur (rest more) (assoc opts :warmup (parse-long (first more))))
      (= a "--duration")    (recur (rest more) (assoc opts :duration (parse-long (first more))))
      (= a "--sse-events")  (recur (rest more) (assoc opts :sse-events (parse-long (first more))))
      (= a "--edn")         (recur more (assoc opts :edn true))
      :else (throw (ex-info (str "Unknown option: " a) {:arg a})))))

(defn -main [& args]
  (let [{:keys [tests edn] :as opts} (parse-args args)
        results (mapv (fn [t]
                        (let [r (run-test t opts)]
                          (print-progress r)
                          r))
                      tests)]
    (when edn
      (prn results)
      (flush))))
