(ns hooks.jolt-ffi
  "clj-kondo hook for jolt.ffi/defcfn.

  `defcfn` binds a C symbol to a Clojure var:

      (ffi/defcfn gtk-box-new \"gtk_box_new\" [:int :int] :pointer)

  clj-kondo cannot see through the macro, so without this hook every bound
  name is an `Unresolved symbol` inside glitter.ffi and an `Unresolved var:
  g/…` at each call site in glitter.widget/glitter.gtk/glitter.app — enough
  noise to make the linter useless as a gate.

  The hook rewrites the form into a `defn` of the same name whose parameter
  count matches the C argument-type vector, and whose body is a literal of
  the declared C return type. That buys three things clj-kondo could not
  otherwise know:

    * the var exists (kills the false positives),
    * its arity — passing the wrong number of arguments to a binding is
      exactly the FFI mistake that otherwise surfaces only as a native
      crash,
    * its return type, so e.g. `(+ 1 (g/gtk-widget-get-first-child …))`
      type-checks.

  Return-type mapping is deliberately conservative: numeric C types
  (including `:pointer`) become a number, `:string` a string, and
  everything else (`:void`) nil.

  This DEVIATES from b12n-rljlt's original in one deliberate way:
  `:pointer` maps to a number here, not nil. rljlt's raylib pointers are
  opaque handles only ever passed back into other untyped `ffi/*` calls, so
  nil cost nothing. glitter.ffi's own ns docstring states pointers are
  \"plain machine addresses (jolt numbers)\", and the codebase actually
  relies on this: `glitter.genum`/`glitter.widget` call `zero?`/arithmetic
  directly on `:pointer`-typed return values (e.g. `(zero? (g/gtk-widget-
  get-prev-sibling old-child))`), which trips a spurious `type-mismatch`
  (\"Expected: number, received: nil\") against a nil-typed stub. Also note
  glitter.ffi spells the GLib size type `:size_t` (underscore, C-style),
  not `:size-t` — both are recognized below in case a future binding uses
  either spelling.

  Adapted from b12n-rljlt's `.clj-kondo/hooks/jolt_ffi.clj` (same
  `jolt.ffi/defcfn` macro, same false-positive problem) — see that repo for
  the original raylib-flavored version of this comment and the un-adapted
  `:pointer -> nil` mapping."
  (:require [clj-kondo.hooks-api :as api]))

(def ^:private numeric-ret
  #{:int :uint :long :ulong :short :ushort :byte :ubyte :float :double
    :size_t :size-t :pointer})

(defn- ret-node
  "A literal whose inferred type matches the declared C return type."
  [ret]
  (let [k (when ret (api/sexpr ret))]
    (cond
      (contains? numeric-ret k) (api/token-node 0)
      (= :string k)             (api/string-node "")
      :else                     (api/token-node nil))))

(defn defcfn
  [{:keys [node]}]
  (let [[_defcfn name-node _c-symbol arg-types ret] (:children node)]
    ;; Only rewrite the shape we understand; anything else falls through to
    ;; the default analysis rather than silently interning a wrong var.
    (if (and name-node arg-types (api/vector-node? arg-types))
      (let [params (map-indexed (fn [i _] (api/token-node (symbol (str "_arg" i))))
                                (:children arg-types))
            expanded (api/list-node
                      [(api/token-node 'clojure.core/defn)
                       name-node
                       (api/vector-node (vec params))
                       (ret-node ret)])]
        {:node (with-meta expanded (meta node))})
      {:node node})))
