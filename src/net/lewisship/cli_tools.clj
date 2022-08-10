(ns net.lewisship.cli-tools
  "Utilities for create CLIs around functions, and creating tools with multiple sub-commands."
  (:require [net.lewisship.cli-tools.impl :as impl]))

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
        command-name' (or (:command-name parsed-interface)
                          (name command-name))
        let-terms (cond-> []
                    (seq option-symbols)
                    (into `[{:keys ~option-symbols} (:options ~command-map-symbol)])

                    (seq arg-symbols)
                    (into `[{:keys ~arg-symbols} (:arguments ~command-map-symbol)]))
        symbol-with-meta (assoc symbol-meta
                                :doc docstring
                                ;; TODO: Override command name as :command <string> in interface
                                ::impl/command-name command-name')]
    `(defn ~command-name
       ~symbol-with-meta
       [arguments#]
       ;; arguments# is normally a seq of strings, from *command-line-arguments*, but for testing,
       ;; it can also be a map with keys :options and :arguments.
       (let [~command-map-symbol (if (map? arguments#)
                                   arguments#
                                   (impl/parse-cli ~command-name'
                                                   arguments#
                                                   ~(dissoc parsed-interface :option-symbols :arg-symbols)))
             ~@let-terms]
         ~@body))))

(defcommand help
  "List available commands"
  []
  (impl/show-tool-help))

(defn- source-of
  [v]
  (str (-> v meta :ns ns-name) "/" (-> v meta :name)))

(defn- resolve-ns
  [ns-symbol]
  (if-let [ns-object (find-ns ns-symbol)]
    ns-object
    (throw (RuntimeException. (format "namespace %s not found (it may need to be required)" (name ns-symbol))))))

(defn locate-commands
  "Passed a seq of symbols identifying *loaded* namespaces, this function
  locates commands, functions defined by [[defcommand]].

  Normally, this is called from [[dispatch]] and is only needed when calling [[dispatch*]] directly.

  Returns a map from string command name to command Var."
  [namespace-symbols]
  (let [f (fn [m namespace-symbol]
            (->> (resolve-ns namespace-symbol)
                 ns-publics
                 vals
                 (reduce (fn [m v]
                           (let [command-name (-> v meta ::impl/command-name)]
                             (cond
                               (nil? command-name)
                               m

                               (contains? m command-name)
                               (throw (RuntimeException. (format "command %s defined by %s conflicts with %s"
                                                                 command-name
                                                                 (source-of v)
                                                                 (source-of (get m command-name)))))

                               :else
                               (assoc m command-name v))))
                         m)))]
    (reduce f {} namespace-symbols)))

(defn dispatch*
  [options]
  "Invoked by [[dispatch]] after namespace and command resolution.

  This can be used, for example, to avoid including the built in help command
  (or when providing an override).

  options:
  - :tool-name - used in command summary and errors
  - :tool-doc - used in command summary
  - :arguments - seq of strings; first is name of command, rest passed to command
  - :commands - map from string command name to a var defined via [[defcommand]]

  Returns nil."
  (impl/dispatch options))

(defn dispatch
  "Locates commands in namespaces, finds the current command
  (as identified by the first command line argument) and processes CLI options and arguments.

  options:
  - :tool-name (required, string) - used in command summary and errors
  - :tool-doc (options, string) - used in help summary
  - :arguments - command line arguments to parse (defaults to *command-line-args*)
  - :namespaces - symbols identifying namespaces to search for commands

  The default for :tool-doc is the docstring of the first namespace.

  dispatch will load any namespaces specified, then scan those namespaces to identify commands.
  It also adds a `help` command.

  If option and argument parsing is unsuccessful, then
  a command usage summary is printed, along with errors, and the program exits
  with error code 1.

  dispatch simply loads and scans the namespaces, adds the `help` command, then calls [[dispatch*]].

  Returns nil."
  [options]
  (let [{:keys [namespaces arguments tool-name tool-doc]} options
        _ (when-not (seq namespaces)
            (throw (ex-info "No :namespaces specified" {:options options})))
        ;; Add this namespace, to include the help command by default
        commands (do
                   ;; Load all the other namespaces first
                   (run! require namespaces)
                   ;; Ensure built-in help command is first
                   (locate-commands (cons 'net.lewisship.cli-tools namespaces)))]
    (dispatch* {:tool-name tool-name
                :tool-doc (or tool-doc
                              (some-> namespaces first find-ns meta :doc))
                :commands commands
                :arguments (or arguments *command-line-args*)})))
