(ns net.lewisship.cli
  "Utilities for create CLIs around functions, and creating tools with multiple sub-commands."
  (:require [net.lewisship.cli.impl :as impl]
            [clojure.pprint :refer [pprint]]))

(defn set-prevent-exit!
  "Normally, after displaying a command summary, `System/exit` is called (with 0 if for --help,
   or 1 if a validation error).

   For testing purposes, this can be prevented; instead, an exception is thrown,
   with message \"Exit\" and ex-data {:status <status>}."
  [flag]
  (alter-var-root #'impl/prevent-exit (constantly flag)))

(defn print-summary
  "Prints the command's summary to `*out*`; partially generated by clojure.tools.cli, and then
  enhanced with more information about positional command line arguments.

  This is often used when a command performs additional validation of its arguments
  and needs to output the summary and errors on failure.

  Uses the command map that is available in `defcommand` function
  (using the :as clause).

  errors is a seq of strings to display as errors."
  [command-map errors]
  (impl/print-summary command-map errors))

(defmacro defcommand
  "Defines a command.

   A command's _interface_ identifies how to parse command options and positional arguments,
   mapping them to local symbols.

   Commands must always have a docstring; this is part of the `-h` / `--help` summary.

   The returned function takes a single parameter; either a seq of strings (command line arguments), or
   a map. The latter is used only for testing, and will have keys :options and :arguments.

   Finally, the body inside a let that destructures the options and positional arguments into local symbols."
  [command-name docstring interface & body]
  (assert (simple-symbol? command-name)
          "defcommand expects a symbol for command name")
  (assert (string? docstring)
          (throw "defcommand requires a docstring"))
  (assert (vector? interface)
          "defcommand expects a vector to define the interface")
  (let [symbol-meta (meta command-name)
        parsed-interface (impl/compile-interface docstring interface)
        {:keys [option-symbols arg-symbols command-map-symbol]
         :or {command-map-symbol (gensym "command-map-")}} parsed-interface
        let-terms (cond-> []
                    (seq option-symbols)
                    (into `[{:keys ~option-symbols} (:options ~command-map-symbol)])

                    (seq arg-symbols)
                    (into `[{:keys ~arg-symbols} (:arguments ~command-map-symbol)]))
        symbol-with-meta (assoc symbol-meta
                                :doc docstring
                                ;; TODO: Override command name as :command <string> in interface
                                ::impl/command-name (name command-name))]
    `(defn ~command-name
       ~symbol-with-meta
       [arguments#]
       ;; arguments# is normally a seq of strings, from *command-line-arguments*, but for testing,
       ;; it can also be a map with keys :options and :arguments.
       (let [~command-map-symbol (if (map? arguments#)
                                   arguments#
                                   (impl/parse-cli ~(name command-name)
                                                   arguments#
                                                   ~(dissoc parsed-interface :option-symbols :arg-symbols)))
             ~@let-terms]
         ~@body))))

(defmacro dispatch
  "Locates commands in namespaces, finds the current command
  (as identified by the first command line argument) and processes CLI options and arguments.

  configuration keys:
  :tool-name (required, string) - used in command summary and errors
  :arguments - command line arguments to parse (defaults to *command-line-args*)
  :namespaces - symbols identifying namespaces to search for commands

  dispatch will load any namespaces specified.

  If option and argument parsing is unsuccessful, then
  a command usage summary is printed, along with errors, and the program exits
  with error code 1."
  [configuration]
  `(try
     (let [conf# ~configuration
           namespace-symbols# (or (:namespaces conf#)
                                 (throw (ex-info "No :namespaces specified" {:configuration conf#})))
           arguments# (or (:arguments conf#)
                          *command-line-args*)
           commands# (impl/locate-commands namespace-symbols#)]
       (impl/dispatch* {:tool-name (:tool-name conf#)
                        :tool-doc (or (:tool-doc conf#)
                                      (some-> namespace-symbols# first find-ns meta :doc))
                        :commands commands#
                        :args arguments#}))
     (catch Exception t#
       (binding [*out* *err*]
         (println "Command failed:" t#)
         (when-let [data# (ex-data t#)]
           (pprint data#)))
       (impl/exit 1))))
