(ns scale-test
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            selmer.util
            [selmer.parser :as selmer]))

(def root-dir "scale-test/uber")

(def command-name-terms
  (str/split "gnip frob update nerd spit snip echo gnop cluster system node module user role acl server service deployment"
             #"\s+"))

(def category-name-terms
  (str/split "kubenetes operations database polylith charts splunk aws datomic clojure monitor github console"
             #"\s+"))

(defn gen-single-command-name
  []
  (let [terms (repeatedly 3 #(rand-nth command-name-terms))]
    (str/join "-" terms)))

(def command-names
  (->> (repeatedly gen-single-command-name)
       distinct))


(defn render
  [from to context-map]
  (let [output      (selmer/render-file (str "templates/" from) context-map)
        output-path (str root-dir "/" to)]
    (println "Writing:" output-path)
    (spit output-path output)))

(defn write-ns
  [group? i command-names]
  (let [category-name (str (rand-nth category-name-terms) "-" i)
        ns-name (str "commands." category-name)
        ns-file (-> ns-name
                    (str/replace "-" "_")
                    (str/replace "." "/"))]
    (render "command-ns.edn"
            (str "src/" ns-file ".clj")
            {:ns            ns-name
             :command-names command-names
             :category      (-> category-name
                                str/capitalize
                                (str/replace #"-" " ")
                                (str " Commands"))
             :group         (when group?
                              category-name)})
    ns-name))

(defn setup
  {:org.babashka/cli {:coerce {:namespaces :long
                               :commands   :long
                               :group      :boolean}}}
  [opts]
  (fs/delete-tree root-dir)
  (fs/create-dirs (str root-dir "/src/commands"))
  (fs/create-dirs (str root-dir "/src/uber"))
  (selmer.util/without-escaping
    (let [{:keys [commands namespaces group]
           :or   {commands   6
                  namespaces 250
                  group      true}} opts
          commands-per-ns (->> command-names
                               (partition commands)
                               (take namespaces))
          ns-names        (doall (map-indexed (partial write-ns group) commands-per-ns))]
      (render "bb.edn" "bb.edn" {})
      (render "deps.edn" "deps.edn" {})
      (render "app.edn" "app" {:ns-names ns-names})
      (render "main.edn" "src/uber/main.clj" {:ns-names ns-names})
      (fs/set-posix-file-permissions (str root-dir "/app") "rwxrwxrwx")
      (printf "%,d namespaces, %,d commands/namespace, %,d total commands%n"
              namespaces commands (* namespaces commands)))))
