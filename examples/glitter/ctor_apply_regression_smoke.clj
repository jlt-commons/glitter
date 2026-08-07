(ns glitter.ctor-apply-regression-smoke
  "Automated smoke against the LIVE GTK tree pinning four real, previously-
  shipped bugs found during round 11's ctor/apply audit — not new
  widgets, regression coverage for existing ones.

  Root cause, verified live via a throwaway probe (not assumed): a
  widget's `:ctor` closure ALWAYS runs with EMPTY props at the real
  glitter.gtk/create-element call site — glitter.core only ever passes
  `(when ns {:ns ns})`, never the hiccup attrs. Real prop values arrive
  afterward through glitter.core's set-attributes -> IRender/set-attribute
  -> glitter.widget/apply-props!, called ONCE PER KEY, never as one
  batched map. Two distinct bug shapes fell out of this:

  1. A `:ctor` branch on a prop with NO corresponding `:apply`-time
     setter never actually applies that prop, ever: `:checkbutton-spec`'s
     `:label` (fixed — gtk_check_button_set_label added to :apply) and
     `:scale-button-spec`'s `:min`/`:max`/`:step` (fixed — via
     gtk_scale_button_get_adjustment + gtk_adjustment_configure, since
     GtkScaleButton has no direct set-range call). Neither bug was ever
     exercised before this fix: :checkbutton's only call site
     (examples/glitter/todo.clj) never passes :label; scale-button's
     default range (0-100) happened to match every existing smoke's
     chosen values.

  2. An `:apply` closure combining MULTIPLE keys into one native call
     with hardcoded fallbacks (`(or (:min p) 0)`) silently clobbers
     whichever key ISN'T present in a given single-key set-attribute
     call: `:scale-spec`'s and `:spin-button-spec`'s `:min`/`:max` (both
     fixed — fall back to the LIVE adjustment's current lower/upper via
     gtk_range_get_adjustment/gtk_spin_button_get_adjustment, not
     hardcoded defaults). Masked since round 1 because scale_smoke.clj's
     own :min 0 :max 100 and spin_button_list_box_smoke.clj's own :min 0
     happened to match the broken fallback closely enough to hide it.

  This smoke changes :min and :max on SEPARATE re-renders (not together
  in one hiccup swap) specifically to exercise the one-key-at-a-time
  call pattern that caused bug #2 — changing them together would not
  have caught the original bug, since a single set-attribute call
  carrying both keys together never existed as a code path in the first
  place (every real render diff is key-by-key)."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]
            [jolt.ffi]))

;; Not bound in glitter.ffi (verification-only) — mirrors the pattern of
;; reading a widget's own state back via a real GTK getter.
(jolt.ffi/defcfn gtk-check-button-get-label "gtk_check_button_get_label" [:pointer] :string)

(defonce state (atom {:cb-label "Check me" :scale-min 5 :scale-max 50 :sb-min 10 :sb-max 20 :sb-step 2}))

(defn view [{:keys [cb-label scale-min scale-max sb-min sb-max sb-step]}]
  [:box {:spacing 4}
   [:checkbutton {:label cb-label}]
   [:scale {:min scale-min :max scale-max}]
   [:scale-button {:min sb-min :max sb-max :step sb-step}]])

(core/set-dispatch! (fn [_event _actions] nil))

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child; :checkbutton/:scale/:scale-button are box's
       ;; three children in sibling order.
       (let [box (g/gtk-widget-get-first-child window)
             cb (g/gtk-widget-get-first-child box)
             scale (g/gtk-widget-get-next-sibling cb)
             sb (g/gtk-widget-get-next-sibling scale)
             scale-adj (g/gtk-range-get-adjustment scale)
             sb-adj (g/gtk-scale-button-get-adjustment sb)]
         (swap! results assoc
                :cb-label-on-mount (gtk-check-button-get-label cb)
                :scale-min-on-mount (g/gtk-adjustment-get-lower scale-adj)
                :scale-max-on-mount (g/gtk-adjustment-get-upper scale-adj)
                :sb-min-on-mount (g/gtk-adjustment-get-lower sb-adj)
                :sb-max-on-mount (g/gtk-adjustment-get-upper sb-adj)
                :sb-step-on-mount (g/gtk-adjustment-get-step-increment sb-adj))
         ;; Re-render 1: change ONLY :scale-max (min unchanged, so it's
         ;; excluded from this render's set-attribute calls entirely) —
         ;; the exact shape that clobbered :min back to 0 before the fix.
         (swap! state assoc :scale-max 80)
         (swap! results assoc
                :scale-min-after-max-only-change (g/gtk-adjustment-get-lower scale-adj)
                :scale-max-after-max-only-change (g/gtk-adjustment-get-upper scale-adj))
         ;; Re-render 2: change ONLY :scale-min this time (max unchanged).
         (swap! state assoc :scale-min 15)
         (swap! results assoc
                :scale-min-after-min-only-change (g/gtk-adjustment-get-lower scale-adj)
                :scale-max-after-min-only-change (g/gtk-adjustment-get-upper scale-adj))
         ;; Same two-step probe for :scale-button (a THIRD dimension,
         ;; :step, alongside :min/:max).
         (swap! state assoc :sb-max 90)
         (swap! results assoc
                :sb-min-after-max-only-change (g/gtk-adjustment-get-lower sb-adj)
                :sb-max-after-max-only-change (g/gtk-adjustment-get-upper sb-adj)
                :sb-step-after-max-only-change (g/gtk-adjustment-get-step-increment sb-adj))
         (swap! state assoc :sb-step 5)
         (swap! results assoc
                :sb-min-after-step-only-change (g/gtk-adjustment-get-lower sb-adj)
                :sb-max-after-step-only-change (g/gtk-adjustment-get-upper sb-adj)
                :sb-step-after-step-only-change (g/gtk-adjustment-get-step-increment sb-adj))
         ;; :checkbutton's :label re-render (a simple redundant-ctor-branch
         ;; case, not a multi-key clobber, but still worth pinning).
         (swap! state assoc :cb-label "Changed")
         (swap! results assoc :cb-label-after-rerender (gtk-check-button-get-label cb))))
     :title "glitter ctor/apply regression smoke" :width 320 :height 100
     :app-id "glitter.ctor-apply-regression-smoke" :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (= "Check me" (:cb-label-on-mount @results))
                   (= 5.0 (:scale-min-on-mount @results))
                   (= 50.0 (:scale-max-on-mount @results))
                   (= 10.0 (:sb-min-on-mount @results))
                   (= 20.0 (:sb-max-on-mount @results))
                   (= 2.0 (:sb-step-on-mount @results))
                   (= 5.0 (:scale-min-after-max-only-change @results))
                   (= 80.0 (:scale-max-after-max-only-change @results))
                   (= 15.0 (:scale-min-after-min-only-change @results))
                   (= 80.0 (:scale-max-after-min-only-change @results))
                   (= 10.0 (:sb-min-after-max-only-change @results))
                   (= 90.0 (:sb-max-after-max-only-change @results))
                   (= 2.0 (:sb-step-after-max-only-change @results))
                   (= 10.0 (:sb-min-after-step-only-change @results))
                   (= 90.0 (:sb-max-after-step-only-change @results))
                   (= 5.0 (:sb-step-after-step-only-change @results))
                   (= "Changed" (:cb-label-after-rerender @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
