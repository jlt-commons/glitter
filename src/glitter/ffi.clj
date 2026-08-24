(ns glitter.ffi
  "Raw C bindings for GTK4 + GLib/GObject/GIO. A thin defcfn layer — no logic.
  The widget layer is built on top of these in glitter.widget, and the
  IRender/IMemory backend that drives it in glitter.gtk.

  Pointers are plain machine addresses (jolt numbers). GTK uses floating
  references for newly created widgets; containers sink the ref when a child is
  appended. The toolkit takes ownership of top-level windows by sinking them via
  g-object-ref-sink, and lets containers manage their children.

  Signal handlers are connected with g-signal-connect-data — the canonical C
  symbol behind the g_signal_connect macro. Handlers are jolt fns wrapped via
  jolt.ffi/foreign-callable (:collect-safe), because GTK invokes them from
  inside the blocking g_application_run main loop. Every :collect-safe
  wrapper in the project goes through this same mechanism — not just
  widget-level event handlers (glitter.gtk for reconciler-driven events,
  glitter.widget/connect-signals! for create!'s direct-props path), but also
  glitter.app's own app-lifecycle callbacks (the \"activate\" signal, the
  auto-quit timeout, and the idle-source used to marshal work onto the main
  thread)."
  (:require [jolt.ffi :as ffi]))

;; --- constants ---------------------------------------------------------------
;; GApplicationFlags — 0 is G_APPLICATION_DEFAULT_FLAGS
(def APPLICATION-DEFAULT-FLAGS 0)

;; GConnectFlags — 0 is no flags (behaves like g_signal_connect)
(def CONNECT-DEFAULT 0)

;; --- GObject enum introspection (table-free constant resolution) -------------
;; Used by glitter.genum to resolve a GEnum member nick (:start, :fill) to its
;; integer value at runtime, with no enum-constant table in the library.
;; g_type_from_name returns the GType (a gsize) for a registered type, or 0 if
;; the type isn't registered yet (GType registration is lazy). g_type_class_ref
;; returns the class struct — for an enum type, a GEnumClass*.
;; g_enum_get_value_by_nick looks a member up by its lowercase nick, returning a
;; GEnumValue* = { gint value; const gchar *value_name; const gchar *value_nick; }
;; whose first field is the integer we want.
(ffi/defcfn g-type-from-name        "g_type_from_name"        [:string] :size_t)
(ffi/defcfn g-type-class-ref        "g_type_class_ref"        [:size_t] :pointer)
(ffi/defcfn g-enum-get-value-by-nick "g_enum_get_value_by_nick" [:pointer :string] :pointer)

;; --- application / main loop (libgio, libglib, libgtk-4) ---------------------
;; gtk_application_new returns a GtkApplication (a GApplication subclass) — needed
;; so gtk_application_window_new can attach managed windows to the app.
(ffi/defcfn gtk-application-new "gtk_application_new" [:string :uint] :pointer)
(ffi/defcfn g-application-new  "g_application_new"  [:string :uint] :pointer)
;; g_application_run blocks running the GTK main loop; mark it :blocking so the
;; GC isn't pinned while it runs and signal callbacks stay collect-safe.
(ffi/defcfn g-application-run  "g_application_run"  [:pointer :int :pointer] :int :blocking)
(ffi/defcfn g-application-quit "g_application_quit" [:pointer] :void)
;; g_timeout_add(interval_ms, GSourceFunc, data) — schedules a callback on the
;; main loop. Used by examples/tests to auto-quit the blocking app loop.
(ffi/defcfn g-timeout-add "g_timeout_add" [:uint :pointer :pointer] :uint)
;; g_idle_add(GSourceFunc, data) — schedules a one-shot callback on the main
;; loop, i.e. the main thread while a GTK app is running. Used to marshal
;; reactive re-renders triggered off the main thread (an nREPL eval mutating a
;; ratom on its worker thread) back onto it. The callback returns 0 (FALSE) so
;; the source runs once and is removed.
(ffi/defcfn g-idle-add "g_idle_add" [:pointer :pointer] :uint)

;; --- windows (libgtk-4) ------------------------------------------------------
(ffi/defcfn gtk-application-window-new "gtk_application_window_new" [:pointer] :pointer)
(ffi/defcfn gtk-window-new             "gtk_window_new"             [] :pointer)
(ffi/defcfn gtk-window-set-title       "gtk_window_set_title"       [:pointer :string] :void)
(ffi/defcfn gtk-window-set-default-size "gtk_window_set_default_size" [:pointer :int :int] :void)
(ffi/defcfn gtk-window-set-child       "gtk_window_set_child"       [:pointer :pointer] :void)
(ffi/defcfn gtk-window-present         "gtk_window_present"         [:pointer] :void)

;; --- boxes / layout containers ----------------------------------------------
(ffi/defcfn gtk-box-new              "gtk_box_new"              [:int :int] :pointer)
(ffi/defcfn gtk-box-append           "gtk_box_append"           [:pointer :pointer] :void)
(ffi/defcfn gtk-box-remove           "gtk_box_remove"           [:pointer :pointer] :void)
(ffi/defcfn gtk-box-reorder-child-after "gtk_box_reorder_child_after" [:pointer :pointer :pointer] :void)
(ffi/defcfn gtk-box-insert-child-after "gtk_box_insert_child_after" [:pointer :pointer :pointer] :void)
(ffi/defcfn gtk-box-set-spacing      "gtk_box_set_spacing"      [:pointer :int] :void)
;; orientation is the GtkOrientable property, not a GtkBox setter
(ffi/defcfn gtk-orientable-set-orientation "gtk_orientable_set_orientation" [:pointer :int] :void)
(ffi/defcfn gtk-box-set-homogeneous  "gtk_box_set_homogeneous"  [:pointer :int] :void)

;; --- widget tree traversal ---------------------------------------------------
;; Walk a container's children in visual order: get_first_child, then
;; get_next_sibling until it returns NULL (0). Lets tests/examples read GTK's
;; actual child order back (e.g. to verify keyed reordering).
(ffi/defcfn gtk-widget-get-first-child  "gtk_widget_get_first_child"  [:pointer] :pointer)
(ffi/defcfn gtk-widget-get-next-sibling "gtk_widget_get_next_sibling" [:pointer] :pointer)
(ffi/defcfn gtk-widget-get-prev-sibling "gtk_widget_get_prev_sibling" [:pointer] :pointer)
;; get-parent: used by :list-box's replace-child!/insert-child-after! to
;; recover a child's live GtkListBoxRow (GTK auto-wraps list-box children in
;; a row, so the row — not the child widget itself — is what carries a
;; position via gtk_list_box_row_get_index).
(ffi/defcfn gtk-widget-get-parent       "gtk_widget_get_parent"       [:pointer] :pointer)

;; --- widgets -----------------------------------------------------------------
(ffi/defcfn gtk-button-new              "gtk_button_new"              [] :pointer)
(ffi/defcfn gtk-button-new-with-label   "gtk_button_new_with_label"   [:string] :pointer)
(ffi/defcfn gtk-button-set-label        "gtk_button_set_label"        [:pointer :string] :void)
;; get-label exists only for smoke-test verification (same shape as
;; gtk-label-get-text/gtk-link-button-get-uri).
(ffi/defcfn gtk-button-get-label        "gtk_button_get_label"        [:pointer] :string)

;; GtkLinkButton extends GtkButton (gtk/gtklinkbutton.h includes
;; gtk/gtkbutton.h — the standard GTK header pattern for a parent-class
;; include), so it inherits "clicked" for free, same reuse story as
;; :toggle-button's "toggled". gtk_button_set_label (above) works on it
;; directly. get-uri exists only for smoke-test verification.
(ffi/defcfn gtk-link-button-new            "gtk_link_button_new"            [:string] :pointer)
(ffi/defcfn gtk-link-button-new-with-label "gtk_link_button_new_with_label" [:string :string] :pointer)
(ffi/defcfn gtk-link-button-set-uri        "gtk_link_button_set_uri"        [:pointer :string] :void)
(ffi/defcfn gtk-link-button-get-uri        "gtk_link_button_get_uri"        [:pointer] :string)

;; gtk_widget_activate simulates a real Enter/Space activation on any
;; focusable widget — for a GtkButton (and subclasses), this is the actual
;; code path that ends in emitting "clicked" (traced through
;; gtk_button_finish_activate in gtk/gtkbutton.c), so it's the correct way
;; to fire a genuine "clicked" signal from a smoke test, not just a
;; same-effect workaround.
(ffi/defcfn gtk-widget-activate "gtk_widget_activate" [:pointer] :int)

(ffi/defcfn gtk-label-new               "gtk_label_new"               [:string] :pointer)
(ffi/defcfn gtk-label-set-text          "gtk_label_set_text"          [:pointer :string] :void)
(ffi/defcfn gtk-label-set-label         "gtk_label_set_label"         [:pointer :string] :void)
(ffi/defcfn gtk-label-set-xalign        "gtk_label_set_xalign"        [:pointer :float] :void)
(ffi/defcfn gtk-label-set-markup        "gtk_label_set_markup"        [:pointer :string] :void)
;; Wrapping/ellipsizing bound a label's natural width so a long line can't drive
;; its container (and a resizable window) ever wider. :wrap + :max-width-chars is
;; the standard fix; :ellipsize is the alternative that truncates with an ellipsis.
(ffi/defcfn gtk-label-set-wrap          "gtk_label_set_wrap"           [:pointer :int] :void)
(ffi/defcfn gtk-label-set-width-chars   "gtk_label_set_width_chars"    [:pointer :int] :void)
(ffi/defcfn gtk-label-set-max-width-chars "gtk_label_set_max_width_chars" [:pointer :int] :void)
(ffi/defcfn gtk-label-set-lines         "gtk_label_set_lines"          [:pointer :int] :void)
(ffi/defcfn gtk-label-set-ellipsize     "gtk_label_set_ellipsize"      [:pointer :int] :void)
(ffi/defcfn gtk-label-get-text          "gtk_label_get_text"          [:pointer] :string)

(ffi/defcfn gtk-entry-new               "gtk_entry_new"               [] :pointer)
;; GtkEditable interface (implemented by GtkEntry):
(ffi/defcfn gtk-editable-get-text       "gtk_editable_get_text"       [:pointer] :string)
(ffi/defcfn gtk-editable-set-text       "gtk_editable_set_text"       [:pointer :string] :void)
(ffi/defcfn gtk-editable-set-placeholder-text "gtk_entry_set_placeholder_text" [:pointer :string] :void)

(ffi/defcfn gtk-checkbutton-new               "gtk_check_button_new"               [] :pointer)
(ffi/defcfn gtk-checkbutton-new-with-label    "gtk_check_button_new_with_label"    [:string] :pointer)
(ffi/defcfn gtk-checkbutton-set-active        "gtk_check_button_set_active"        [:pointer :int] :void)
(ffi/defcfn gtk-checkbutton-get-active        "gtk_check_button_get_active"        [:pointer] :int)
;; set-label added round 11, fixing a real pre-existing bug: :ctor branched on
;; (:label p) to pick new-with-label vs. new, but :apply never re-applied
;; :label afterward — and :ctor's props are ALWAYS empty at the real
;; create-element call site (confirmed live, see glitter.gtk's set-attribute
;; comment), so [:checkbutton {:label "x"}] silently rendered with NO label
;; text, ever. Only usage in this project (todo.clj) never passed :label, so
;; it shipped unnoticed across 10 rounds.
(ffi/defcfn gtk-checkbutton-set-label         "gtk_check_button_set_label"         [:pointer :string] :void)

;; GtkToggleButton — a *separate* GTK4 class from GtkCheckButton (they were
;; related pre-GTK4; not anymore), but its "toggled" signal has the exact
;; void(widget, user_data) shape already registered as :on-toggled, so no new
;; signal wiring is needed — only its own set/get-active pair (can't reuse
;; checkbutton's; different FFI functions despite the identical shape).
(ffi/defcfn gtk-toggle-button-new             "gtk_toggle_button_new"             [] :pointer)
(ffi/defcfn gtk-toggle-button-new-with-label  "gtk_toggle_button_new_with_label"  [:string] :pointer)
(ffi/defcfn gtk-toggle-button-set-active      "gtk_toggle_button_set_active"      [:pointer :int] :void)
(ffi/defcfn gtk-toggle-button-get-active      "gtk_toggle_button_get_active"      [:pointer] :int)

;; GtkSwitch's real interaction signal, "state-set", does NOT fit the
;; uniform void(widget, user_data) shape every signal above uses — it's
;; gboolean (*)(GtkSwitch*, gboolean, gpointer), 3 args + non-void return
;; (confirmed against gtk/gtkswitch.c's g_signal_new call). See
;; glitter.widget's signal-callable-shape table and
;; docs/guide/gtk-widget-layer.md for the full mechanism this required.
(ffi/defcfn gtk-switch-new        "gtk_switch_new"        [] :pointer)
(ffi/defcfn gtk-switch-set-active "gtk_switch_set_active" [:pointer :int] :void)
(ffi/defcfn gtk-switch-get-active "gtk_switch_get_active" [:pointer] :int)

(ffi/defcfn gtk-separator-new           "gtk_separator_new"           [:int] :pointer)

;; --- spinner (indeterminate "loading" indicator) ------------------------------
;; No signals of interest — a display-only widget, driven entirely by :spinning.
;; get-spinning exists only for smoke-test verification, same reasoning as
;; gtk-widget-has-css-class.
(ffi/defcfn gtk-spinner-new          "gtk_spinner_new"          [] :pointer)
(ffi/defcfn gtk-spinner-set-spinning "gtk_spinner_set_spinning" [:pointer :int] :void)
(ffi/defcfn gtk-spinner-get-spinning "gtk_spinner_get_spinning" [:pointer] :int)

;; --- progress bar --------------------------------------------------------------
;; Display-only, same as spinner — no signals; driven by :fraction/:text.
;; get-fraction exists only for smoke-test verification.
(ffi/defcfn gtk-progress-bar-new           "gtk_progress_bar_new"           [] :pointer)
(ffi/defcfn gtk-progress-bar-set-fraction  "gtk_progress_bar_set_fraction"  [:pointer :double] :void)
(ffi/defcfn gtk-progress-bar-set-text      "gtk_progress_bar_set_text"      [:pointer :string] :void)
(ffi/defcfn gtk-progress-bar-set-show-text "gtk_progress_bar_set_show_text" [:pointer :int] :void)
(ffi/defcfn gtk-progress-bar-get-fraction  "gtk_progress_bar_get_fraction"  [:pointer] :double)

;; --- image (icon-name or file) --------------------------------------------------
;; Display-only, same as spinner/progress-bar — no signals. GtkImage's storage
;; type (icon vs. file vs. paintable) switches automatically per whichever
;; setter was called last, so re-applying a different key on re-render just
;; works with no explicit "clear" step needed. get-icon-name exists only for
;; smoke-test verification.
(ffi/defcfn gtk-image-new                "gtk_image_new"                [] :pointer)
(ffi/defcfn gtk-image-new-from-icon-name "gtk_image_new_from_icon_name" [:string] :pointer)
(ffi/defcfn gtk-image-new-from-file      "gtk_image_new_from_file"      [:string] :pointer)
(ffi/defcfn gtk-image-set-from-icon-name "gtk_image_set_from_icon_name" [:pointer :string] :void)
(ffi/defcfn gtk-image-set-from-file      "gtk_image_set_from_file"      [:pointer :string] :void)
(ffi/defcfn gtk-image-set-pixel-size     "gtk_image_set_pixel_size"     [:pointer :int] :void)
(ffi/defcfn gtk-image-get-icon-name      "gtk_image_get_icon_name"      [:pointer] :string)

;; --- level bar (a gauge/indicator, e.g. battery or volume level) ---------------
;; Display-only, same as spinner/progress-bar/image — no signals; driven by
;; :value/:min-value/:max-value/:inverted. :mode (continuous vs. discrete
;; segments) is out of scope for v1, same minimal-viable-display-widget
;; scope as progress-bar. get-value exists only for smoke-test verification.
(ffi/defcfn gtk-level-bar-new           "gtk_level_bar_new"           [] :pointer)
(ffi/defcfn gtk-level-bar-set-value     "gtk_level_bar_set_value"     [:pointer :double] :void)
(ffi/defcfn gtk-level-bar-set-min-value "gtk_level_bar_set_min_value" [:pointer :double] :void)
(ffi/defcfn gtk-level-bar-set-max-value "gtk_level_bar_set_max_value" [:pointer :double] :void)
(ffi/defcfn gtk-level-bar-set-inverted  "gtk_level_bar_set_inverted"  [:pointer :int] :void)
(ffi/defcfn gtk-level-bar-get-value     "gtk_level_bar_get_value"     [:pointer] :double)

;; --- revealer (single-child container, animated show/hide) -------------------
;; Display-only, same shape as spinner/progress-bar/image/level-bar — no
;; signal to wire (driven entirely by :reveal-child/:transition-type/
;; :transition-duration). set-child makes it a single-child container, same
;; strategy as :frame/:scrolled. get-reveal-child/get-child-revealed/
;; get-transition-duration/get-transition-type exist only for smoke
;; verification — get-child-revealed in particular distinguishes "revealed
;; requested" (:reveal-child) from "revealed and the show animation has
;; actually finished" (:child-revealed), a real GtkRevealer distinction.
(ffi/defcfn gtk-revealer-new                     "gtk_revealer_new"                     [] :pointer)
(ffi/defcfn gtk-revealer-set-child               "gtk_revealer_set_child"               [:pointer :pointer] :void)
(ffi/defcfn gtk-revealer-set-reveal-child        "gtk_revealer_set_reveal_child"        [:pointer :int] :void)
(ffi/defcfn gtk-revealer-get-reveal-child        "gtk_revealer_get_reveal_child"        [:pointer] :int)
(ffi/defcfn gtk-revealer-get-child-revealed      "gtk_revealer_get_child_revealed"      [:pointer] :int)
(ffi/defcfn gtk-revealer-set-transition-type     "gtk_revealer_set_transition_type"     [:pointer :int] :void)
(ffi/defcfn gtk-revealer-get-transition-type     "gtk_revealer_get_transition_type"     [:pointer] :int)
(ffi/defcfn gtk-revealer-set-transition-duration "gtk_revealer_set_transition_duration" [:pointer :uint] :void)
(ffi/defcfn gtk-revealer-get-transition-duration "gtk_revealer_get_transition_duration" [:pointer] :uint)

;; --- center box (fixed 3-slot layout: start/center/end) -----------------------
;; Unlike :box (append-many, ordered) or :frame/:scrolled/:revealer
;; (exactly one child), GtkCenterBox has three independently addressable
;; NAMED slots. glitter.widget's :center-box container strategy picks the
;; first empty slot (via the getters below returning null/0) on append, and
;; finds which slot a child occupies (again via the getters) on remove/
;; replace — see gtk-widget-layer.md for why this needed no new plumbing in
;; glitter.gtk at all, unlike :switch's set-event-handler generalization.
(ffi/defcfn gtk-center-box-new                "gtk_center_box_new"                [] :pointer)
(ffi/defcfn gtk-center-box-set-start-widget   "gtk_center_box_set_start_widget"   [:pointer :pointer] :void)
(ffi/defcfn gtk-center-box-get-start-widget   "gtk_center_box_get_start_widget"   [:pointer] :pointer)
(ffi/defcfn gtk-center-box-set-center-widget  "gtk_center_box_set_center_widget"  [:pointer :pointer] :void)
(ffi/defcfn gtk-center-box-get-center-widget  "gtk_center_box_get_center_widget"  [:pointer] :pointer)
(ffi/defcfn gtk-center-box-set-end-widget     "gtk_center_box_set_end_widget"     [:pointer :pointer] :void)
(ffi/defcfn gtk-center-box-get-end-widget     "gtk_center_box_get_end_widget"     [:pointer] :pointer)

;; --- spin button (numeric entry with up/down steppers) ------------------------
;; gtk_spin_button_new_with_range builds its own internal GtkAdjustment, same
;; "no separate GtkAdjustment binding needed" shape as :scale. Its
;; "value-changed" signal is confirmed via gtk/gtkspinbutton.c's g_signal_new
;; call to be g_signal_new(..., G_TYPE_NONE, 0) — the plain 2-arg-void shape,
;; unlike :switch's "state-set" — but it's the SAME SIGNAL NAME GtkScale
;; already uses, read back through a DIFFERENT getter
;; (gtk_spin_button_get_value, not gtk_range_get_value). See
;; gtk-widget-layer.md's "generalizing signal-value by tag" section for why
;; that collision needed glitter.widget's signal-value table re-keyed by
;; [tag signal] instead of bare signal name.
(ffi/defcfn gtk-spin-button-new-with-range "gtk_spin_button_new_with_range" [:double :double :double] :pointer)
(ffi/defcfn gtk-spin-button-set-range      "gtk_spin_button_set_range"      [:pointer :double :double] :void)
(ffi/defcfn gtk-spin-button-set-value      "gtk_spin_button_set_value"      [:pointer :double] :void)
(ffi/defcfn gtk-spin-button-get-value      "gtk_spin_button_get_value"      [:pointer] :double)
(ffi/defcfn gtk-spin-button-set-digits     "gtk_spin_button_set_digits"     [:pointer :int] :void)
(ffi/defcfn gtk-spin-button-set-increments "gtk_spin_button_set_increments" [:pointer :double :double] :void)
;; get-adjustment added round 11, fixing the SAME real pre-existing bug as
;; :scale-spec's own gtk-range-get-adjustment (see its comment) — the
;; identical :min/:max hardcoded-fallback clobbering shape, masked since
;; round 1 because spin_button_list_box_smoke.clj's own :min 0 happened to
;; match the broken fallback (its :max 10, however, WAS at risk — the bug's
;; actual manifestation was order-dependent on which key set-attribute
;; happened to deliver last).
(ffi/defcfn gtk-spin-button-get-adjustment "gtk_spin_button_get_adjustment" [:pointer] :pointer)

;; --- list box (a selectable-row list container) --------------------------------
;; append/remove/insert take the CHILD widget directly (gtk_list_box_append
;; auto-wraps it in a GtkListBoxRow internally, same "child widget, not the
;; row" shape as gtk_box_append/gtk_box_remove) — confirmed against
;; gtk/gtklistbox.h. insert's `position` clamps out-of-range/-1 to append at
;; the end (confirmed via its doc comment in gtk/gtklistbox.c), which is what
;; makes replace-child!'s remove-then-reinsert-at-captured-index safe. v1
;; scope: :list-box supports append/remove/replace correctly but does NOT
;; support keyed reorder or mid-list positional insert (documented gap, same
;; "single/fixed-slot container" carve-out precedent as :frame/:scrolled/
;; :window use for reorder-child!/insert-child-after! — see
;; gtk-widget-layer.md). row-get-index/get-selected-row back
;; :on-row-selected/:on-row-activated's value-fn, which reads the box's OWN
;; selection state back AFTER the signal fires — verified against
;; gtk/gtklistbox.c's gtk_list_box_select_and_activate_full, which selects a
;; row BEFORE emitting row-activated (activate-single-click defaults to
;; TRUE), so by the time either signal's handler runs,
;; gtk_list_box_get_selected_row already reflects the activated row.
(ffi/defcfn gtk-list-box-new              "gtk_list_box_new"              [] :pointer)
(ffi/defcfn gtk-list-box-append           "gtk_list_box_append"           [:pointer :pointer] :void)
(ffi/defcfn gtk-list-box-remove           "gtk_list_box_remove"           [:pointer :pointer] :void)
(ffi/defcfn gtk-list-box-insert           "gtk_list_box_insert"           [:pointer :pointer :int] :void)
(ffi/defcfn gtk-list-box-get-selected-row "gtk_list_box_get_selected_row" [:pointer] :pointer)
;; select-row exists only for smoke-test verification, to trigger a real
;; "row-selected" emission the same way :switch's smoke bypasses
;; set-switch-active! to call gtk_switch_set_active directly — confirmed
;; against gtk/gtklistbox.c's gtk_list_box_select_row_internal that it
;; DOES emit signals[ROW_SELECTED] with the real row arg.
(ffi/defcfn gtk-list-box-select-row       "gtk_list_box_select_row"       [:pointer :pointer] :void)
(ffi/defcfn gtk-list-box-row-get-index    "gtk_list_box_row_get_index"    [:pointer] :int)

;; --- generic widget state & layout -------------------------------------------
;; The margin/halign/hexpand setters are GtkWidget props — they apply to every
;; widget, not just a specific kind, so glitter.widget applies them to all tags.
;; halign/valign take a GtkAlign enum value, resolved at runtime by glitter.genum
;; from an idiomatic keyword nick (:start, :fill, :center).
(ffi/defcfn gtk-widget-set-visible    "gtk_widget_set_visible"    [:pointer :int] :void)
;; get-visible added for :popover's suppressing-guard setter (round 10) — no
;; earlier widget here needed to READ its own visibility back.
(ffi/defcfn gtk-widget-get-visible    "gtk_widget_get_visible"    [:pointer] :int)
(ffi/defcfn gtk-widget-set-sensitive  "gtk_widget_set_sensitive"  [:pointer :int] :void)
;; get-sensitive added while verifying crud.clj — the first live check of
;; :sensitive's own read-back (every prior use only ever set it). Reads
;; the WIDGET'S OWN sensitive property, not ancestor-aware effective
;; sensitivity (that's gtk_widget_is_sensitive, not bound here — nothing
;; in this project needs it yet).
(ffi/defcfn gtk-widget-get-sensitive  "gtk_widget_get_sensitive"  [:pointer] :int)
(ffi/defcfn gtk-widget-set-tooltip-text "gtk_widget_set_tooltip_text" [:pointer :string] :void)
(ffi/defcfn gtk-widget-set-margin-start   "gtk_widget_set_margin_start"   [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-margin-end     "gtk_widget_set_margin_end"     [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-margin-top     "gtk_widget_set_margin_top"     [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-margin-bottom  "gtk_widget_set_margin_bottom"  [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-halign "gtk_widget_set_halign" [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-valign "gtk_widget_set_valign" [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-hexpand "gtk_widget_set_hexpand" [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-vexpand "gtk_widget_set_vexpand" [:pointer :int] :void)
(ffi/defcfn gtk-widget-set-size-request "gtk_widget_set_size_request" [:pointer :int :int] :void)
;; CSS style classes — GTK4's actual per-widget styling hook: no "inline
;; style" API the way DOM has (element.style.color = ...), only named
;; classes matched against CSS rules (built-in ones like "suggested-action"/
;; "destructive-action"/"flat"/"pill" work with zero app-provided CSS).
(ffi/defcfn gtk-widget-add-css-class    "gtk_widget_add_css_class"    [:pointer :string] :void)
(ffi/defcfn gtk-widget-remove-css-class "gtk_widget_remove_css_class" [:pointer :string] :void)
(ffi/defcfn gtk-widget-has-css-class    "gtk_widget_has_css_class"    [:pointer :string] :int)

;; --- frame (single-child container with an optional label) -------------------
(ffi/defcfn gtk-frame-new       "gtk_frame_new"       [:string] :pointer)
(ffi/defcfn gtk-frame-set-label "gtk_frame_set_label" [:pointer :string] :void)
(ffi/defcfn gtk-frame-set-child "gtk_frame_set_child" [:pointer :pointer] :void)

;; --- scrolled window (single-child viewport that scrolls instead of growing) --
;; GTK4 propagates the child's natural size by default, which would make a
;; scrolled window grow to fit its child rather than scroll. The toolkit turns
;; propagation off at construction so the child scrolls within the allotted area.
(ffi/defcfn gtk-scrolled-window-new "gtk_scrolled_window_new" [:pointer :pointer] :pointer)
(ffi/defcfn gtk-scrolled-window-set-child "gtk_scrolled_window_set_child" [:pointer :pointer] :void)
(ffi/defcfn gtk-scrolled-window-set-propagate-natural-height
  "gtk_scrolled_window_set_propagate_natural_height" [:pointer :int] :void)
(ffi/defcfn gtk-scrolled-window-set-propagate-natural-width
  "gtk_scrolled_window_set_propagate_natural_width" [:pointer :int] :void)

;; --- scale (slider) -----------------------------------------------------------
;; gtk_scale_new_with_range builds its own internal GtkAdjustment, so no
;; separate GtkAdjustment binding is needed here — the get/set-value and
;; set-range/set-increments calls below are GtkRange's API (GtkScale extends
;; GtkRange and inherits it), operating on that internal adjustment directly.
(ffi/defcfn gtk-scale-new-with-range "gtk_scale_new_with_range" [:int :double :double :double] :pointer)
(ffi/defcfn gtk-scale-set-digits     "gtk_scale_set_digits"     [:pointer :int] :void)
(ffi/defcfn gtk-scale-set-draw-value "gtk_scale_set_draw_value" [:pointer :int] :void)
(ffi/defcfn gtk-range-set-value      "gtk_range_set_value"      [:pointer :double] :void)
(ffi/defcfn gtk-range-get-value      "gtk_range_get_value"      [:pointer] :double)
(ffi/defcfn gtk-range-set-range      "gtk_range_set_range"      [:pointer :double :double] :void)
(ffi/defcfn gtk-range-set-increments "gtk_range_set_increments" [:pointer :double :double] :void)
;; get-adjustment added round 11, fixing a real pre-existing bug in :apply's
;; :min/:max handling — see gtk-adjustment-get-lower's own comment (near
;; gtk-scale-button-get-adjustment) for the full mechanism: set-attribute
;; delivers exactly one changed key per call, so a hardcoded 0/100 fallback
;; for whichever of :min/:max ISN'T in that particular call silently
;; clobbers the other. gtk_range_get_adjustment (GtkScale inherits it from
;; GtkRange) is the read side needed to fall back to the CURRENT bound
;; instead. Confirmed live: scale_smoke.clj's own :min 0 :max 100 happened
;; to match the broken fallback exactly, masking the bug since round 1.
(ffi/defcfn gtk-range-get-adjustment "gtk_range_get_adjustment" [:pointer] :pointer)

;; --- password entry (obscured-text GtkEditable) -------------------------------
;; Implements GtkEditable via a delegate (confirmed against
;; gtk/gtkpasswordentry.c: gtk_editable_init_delegate + get_delegate
;; returning an inner GtkText, and that the delegate helper itself
;; connects to the inner widget's "changed" and re-emits it on the outer
;; object — gtk/gtkeditable.c's gtk_editable_init_delegate,
;; g_signal_connect(delegate, "changed", ...)) — so
;; gtk_editable_set_text/get_text and glitter.widget's existing
;; set-entry-text!/:entry "changed" signal entry work directly on a
;; GtkPasswordEntry pointer with no new plumbing.
(ffi/defcfn gtk-password-entry-new                "gtk_password_entry_new"                [] :pointer)
(ffi/defcfn gtk-password-entry-set-show-peek-icon "gtk_password_entry_set_show_peek_icon" [:pointer :int] :void)
(ffi/defcfn gtk-password-entry-get-show-peek-icon "gtk_password_entry_get_show_peek_icon" [:pointer] :int)

;; --- search entry (GtkEditable + a debounced "search-changed" signal) --------
;; Same GtkEditable-delegate reuse story as password entry (confirmed
;; against gtk/gtksearchentry.c: also calls gtk_editable_init_delegate).
;; "search-changed" is genuinely new — confirmed via gtk/gtksearchentry.c's
;; g_signal_new call to be G_TYPE_NONE, 0, the plain 2-arg-void shape, no
;; set-event-handler generalization needed. Fires debounced (after
;; :search-delay ms of no typing), unlike "changed" which fires on every
;; keystroke.
(ffi/defcfn gtk-search-entry-new              "gtk_search_entry_new"              [] :pointer)
(ffi/defcfn gtk-search-entry-set-search-delay "gtk_search_entry_set_search_delay" [:pointer :uint] :void)
(ffi/defcfn gtk-search-entry-get-search-delay "gtk_search_entry_get_search_delay" [:pointer] :uint)

;; --- expander (single-child, collapsible disclosure section) -----------------
;; gtk_expander_set_child makes it a single-child container — same
;; strategy as :frame/:scrolled/:revealer. Confirmed against
;; gtk/gtkexpander.c: it has NO g_signal_new call of its own — real
;; interactivity means watching "notify::expanded", a GObject
;; property-change signal that reuses the exact 3-arg-void callable shape
;; already generalized for :list-box's "row-selected"/"row-activated" —
;; see glitter.gtk/set-event-handler.
(ffi/defcfn gtk-expander-new          "gtk_expander_new"          [:string] :pointer)
(ffi/defcfn gtk-expander-set-label    "gtk_expander_set_label"    [:pointer :string] :void)
(ffi/defcfn gtk-expander-get-label    "gtk_expander_get_label"    [:pointer] :string)
(ffi/defcfn gtk-expander-set-expanded "gtk_expander_set_expanded" [:pointer :int] :void)
(ffi/defcfn gtk-expander-get-expanded "gtk_expander_get_expanded" [:pointer] :int)
(ffi/defcfn gtk-expander-set-child    "gtk_expander_set_child"    [:pointer :pointer] :void)

;; --- paned (2-slot resizable split view) --------------------------------------
;; Two independently addressable NAMED slots (start/end), like
;; :center-box's three but simpler — confirmed against gtk/gtkpaned.h.
;; Inherits the SAME structural v1 gap :center-box has (see
;; gtk-widget-layer.md and center-box-insert-after!'s docstring): no
;; transient capacity for a 3rd simultaneous occupant, so a same-slot
;; hiccup TAG swap while both slots are full is unsupported — documented
;; up front this time, not discovered live. GtkPaned implements
;; GtkOrientable (confirmed: G_IMPLEMENT_INTERFACE (GTK_TYPE_ORIENTABLE,
;; NULL) in gtk/gtkpaned.c), so orientation can use the SAME
;; construct-then-correct-via-:apply pattern :box/:separator already use,
;; unlike :scale's stricter must-resolve-at-construction constraint.
;; :position (the divider's pixel offset) drives real interactivity via
;; "notify::position" — another free reuse of the 3-arg-void shape.
(ffi/defcfn gtk-paned-new             "gtk_paned_new"             [:int] :pointer)
(ffi/defcfn gtk-paned-set-start-child "gtk_paned_set_start_child" [:pointer :pointer] :void)
(ffi/defcfn gtk-paned-get-start-child "gtk_paned_get_start_child" [:pointer] :pointer)
(ffi/defcfn gtk-paned-set-end-child   "gtk_paned_set_end_child"   [:pointer :pointer] :void)
(ffi/defcfn gtk-paned-get-end-child   "gtk_paned_get_end_child"   [:pointer] :pointer)
(ffi/defcfn gtk-paned-set-position    "gtk_paned_set_position"    [:pointer :int] :void)
(ffi/defcfn gtk-paned-get-position    "gtk_paned_get_position"    [:pointer] :int)

;; --- aspect frame (single-child container, maintains an aspect ratio) --------
;; gtk_aspect_frame_set_child makes it a single-child container — same
;; strategy as :frame/:scrolled/:revealer/:expander. All four construction
;; params (xalign/yalign/ratio/obey-child) are individually re-settable
;; post-construction too (confirmed against gtk/gtkaspectframe.h), unlike
;; :scale's/:paned's orientation, which needs a GType registered before it
;; can be resolved — plain floats/bool carry no such registration risk.
;; get-xalign/get-yalign/get-ratio/get-obey-child exist only for smoke-test
;; verification (same shape as gtk-level-bar-get-value/gtk-button-get-label).
(ffi/defcfn gtk-aspect-frame-new             "gtk_aspect_frame_new"             [:float :float :float :int] :pointer)
(ffi/defcfn gtk-aspect-frame-set-child       "gtk_aspect_frame_set_child"       [:pointer :pointer] :void)
(ffi/defcfn gtk-aspect-frame-get-child       "gtk_aspect_frame_get_child"       [:pointer] :pointer)
(ffi/defcfn gtk-aspect-frame-set-xalign      "gtk_aspect_frame_set_xalign"      [:pointer :float] :void)
(ffi/defcfn gtk-aspect-frame-get-xalign      "gtk_aspect_frame_get_xalign"      [:pointer] :float)
(ffi/defcfn gtk-aspect-frame-set-yalign      "gtk_aspect_frame_set_yalign"      [:pointer :float] :void)
(ffi/defcfn gtk-aspect-frame-get-yalign      "gtk_aspect_frame_get_yalign"      [:pointer] :float)
(ffi/defcfn gtk-aspect-frame-set-ratio       "gtk_aspect_frame_set_ratio"       [:pointer :float] :void)
(ffi/defcfn gtk-aspect-frame-get-ratio       "gtk_aspect_frame_get_ratio"       [:pointer] :float)
(ffi/defcfn gtk-aspect-frame-set-obey-child  "gtk_aspect_frame_set_obey_child"  [:pointer :int] :void)
(ffi/defcfn gtk-aspect-frame-get-obey-child  "gtk_aspect_frame_get_obey_child"  [:pointer] :int)

;; --- calendar (date picker) ---------------------------------------------------
;; "day-selected" is confirmed via gtk/gtkcalendar.c's g_signal_new to be the
;; plain 2-arg-void shape, but gtk_calendar_get_date returns a GDateTime* — a
;; genuinely new value type this project hasn't marshalled before. GDateTime
;; is refcounted (glib/gdatetime.h): gtk_calendar_get_date's own C body calls
;; g_date_time_ref internally (confirmed by reading it directly, not assumed),
;; so the caller owns a NEW ref and must g_date_time_unref it; a date
;; constructed via g_date_time_new_local is likewise caller-owned and must be
;; unref'd after gtk_calendar_select_day (which does not take ownership of an
;; in-param, the standard GLib convention) — every call site in
;; glitter.widget's set-calendar-date!/signal-value entry unrefs what it refs,
;; or every render/dispatch would leak one GDateTime object.
(ffi/defcfn gtk-calendar-new              "gtk_calendar_new"              [] :pointer)
(ffi/defcfn gtk-calendar-select-day       "gtk_calendar_select_day"       [:pointer :pointer] :void)
(ffi/defcfn gtk-calendar-get-date         "gtk_calendar_get_date"         [:pointer] :pointer)
(ffi/defcfn g-date-time-new-local         "g_date_time_new_local"         [:int :int :int :int :int :double] :pointer)
(ffi/defcfn g-date-time-get-year          "g_date_time_get_year"          [:pointer] :int)
(ffi/defcfn g-date-time-get-month         "g_date_time_get_month"         [:pointer] :int)
(ffi/defcfn g-date-time-get-day-of-month  "g_date_time_get_day_of_month"  [:pointer] :int)
(ffi/defcfn g-date-time-unref             "g_date_time_unref"             [:pointer] :void)

;; --- overlay (one main child, N floating overlay children) -------------------
;; A genuinely different shape from every other multi-child container here:
;; :box is an ordered append-list, :center-box/:paned are fixed NAMED slots
;; queried live via per-slot getters — GtkOverlay has exactly ONE queryable
;; slot (gtk_overlay_get_child, the main content) and an UNBOUNDED set of
;; overlay children with NO enumeration getter at all (confirmed: no
;; "get overlays" function in gtk/gtkoverlay.h), so occupancy for the
;; overlay set specifically can't be queried live the way every other
;; container here does. glitter.widget's overlay-* container functions
;; treat the FIRST hiccup child as main and every subsequent child as an
;; overlay unconditionally — see their own docstrings for the resulting
;; v1 scope (no main-slot tag swap once occupied, same class of gap
;; :center-box/:paned document, plus overlay-replace-child! not
;; preserving z-order position on a tag swap).
(ffi/defcfn gtk-overlay-new             "gtk_overlay_new"             [] :pointer)
(ffi/defcfn gtk-overlay-set-child       "gtk_overlay_set_child"       [:pointer :pointer] :void)
(ffi/defcfn gtk-overlay-get-child       "gtk_overlay_get_child"       [:pointer] :pointer)
(ffi/defcfn gtk-overlay-add-overlay     "gtk_overlay_add_overlay"     [:pointer :pointer] :void)
(ffi/defcfn gtk-overlay-remove-overlay  "gtk_overlay_remove_overlay"  [:pointer :pointer] :void)

;; --- flow box (a flowing/wrapping sibling to :list-box) -----------------------
;; append/insert auto-wrap a plain child in a GtkFlowBoxChild — same shape
;; as :list-box's GtkListBoxRow auto-wrap (confirmed against
;; gtk/gtkflowbox.c's gtk_flow_box_insert body) — but gtk_flow_box_remove
;; does NOT share :list-box's gtk_list_box_remove gotcha: its C body
;; explicitly accepts EITHER the wrapped GtkFlowBoxChild OR the plain
;; inner widget, auto-unwrapping via gtk_widget_get_parent internally
;; (confirmed by reading its body directly — do not assume this transfers
;; from :list-box's stricter behavior just because the widgets are
;; siblings). "child-activated" is confirmed via g_signal_new to be
;; void(GtkFlowBox*, GtkFlowBoxChild*, gpointer) — the same 3-arg-void
;; shape already generalized for :list-box/:expander/:paned, another free
;; reuse. v1 deliberately does NOT wire a value-fn for it (would need
;; GList marshalling via gtk_flow_box_get_selected_children, a new FFI
;; complexity class not needed for a first pass) — :on-child-activated
;; dispatches with no :glitter/value, same as :on-click/:on-toggled
;; without an explicit value-fn.
(ffi/defcfn gtk-flow-box-new                 "gtk_flow_box_new"                 [] :pointer)
(ffi/defcfn gtk-flow-box-append              "gtk_flow_box_append"              [:pointer :pointer] :void)
(ffi/defcfn gtk-flow-box-remove              "gtk_flow_box_remove"              [:pointer :pointer] :void)
(ffi/defcfn gtk-flow-box-insert              "gtk_flow_box_insert"              [:pointer :pointer :int] :void)
(ffi/defcfn gtk-flow-box-child-get-index     "gtk_flow_box_child_get_index"     [:pointer] :int)

;; --- picture (a modernized :image, GdkPaintable-based) ------------------------
;; No signal (confirmed: no g_signal_new in gtk/gtkpicture.c) — display-only,
;; same shape as :image/:spinner. new-for-filename/set-filename take a plain
;; string, not a GFile* — the same convention :image's own
;; gtk-image-new-from-file/gtk-image-set-from-file already use, kept for
;; consistency. :content-fit resolves a GtkContentFit nick (:fill/:contain/
;; :cover/:scale-down) via glitter.genum, the same runtime lookup :halign/
;; :transition-type already use — no hardcoded nick table here either.
(ffi/defcfn gtk-picture-new                  "gtk_picture_new"                  [] :pointer)
(ffi/defcfn gtk-picture-new-for-filename     "gtk_picture_new_for_filename"     [:string] :pointer)
(ffi/defcfn gtk-picture-set-filename         "gtk_picture_set_filename"         [:pointer :string] :void)
(ffi/defcfn gtk-picture-set-content-fit      "gtk_picture_set_content_fit"      [:pointer :int] :void)
(ffi/defcfn gtk-picture-get-content-fit      "gtk_picture_get_content_fit"      [:pointer] :int)
(ffi/defcfn gtk-picture-set-can-shrink       "gtk_picture_set_can_shrink"       [:pointer :int] :void)
(ffi/defcfn gtk-picture-get-can-shrink       "gtk_picture_get_can_shrink"       [:pointer] :int)
(ffi/defcfn gtk-picture-set-alternative-text "gtk_picture_set_alternative_text" [:pointer :string] :void)
(ffi/defcfn gtk-picture-get-alternative-text "gtk_picture_get_alternative_text" [:pointer] :string)

;; --- editable label (click-to-edit label, GtkEditable delegate) --------------
;; A THIRD widget implementing GtkEditable via the same delegate pattern
;; :password-entry/:search-entry use (confirmed: gtk/gtkeditablelabel.c
;; calls gtk_editable_init_delegate) — reuses set-entry-text!/:entry's
;; "changed" signal name for free, needing its own [tag "changed"]
;; signal-value entry (the lesson from :password-entry's near-miss,
;; applied proactively this time). No signal of its own — click-to-edit
;; is GTK's own built-in gesture, nothing glitter needs to wire.
;; get-editing exists so set-editable-label-editing! can compare
;; current-vs-desired before calling start/stop, the usual
;; set-compare-suppress shape.
(ffi/defcfn gtk-editable-label-new           "gtk_editable_label_new"           [:string] :pointer)
(ffi/defcfn gtk-editable-label-get-editing   "gtk_editable_label_get_editing"   [:pointer] :int)
(ffi/defcfn gtk-editable-label-start-editing "gtk_editable_label_start_editing" [:pointer] :void)
(ffi/defcfn gtk-editable-label-stop-editing  "gtk_editable_label_stop_editing"  [:pointer :int] :void)

;; --- notebook (tabbed container) ----------------------------------------------
;; Unlike :list-box/:flow-box, GtkNotebook does NOT auto-wrap children in an
;; opaque row/child object — `child` IS the real widget throughout, and
;; gtk_notebook_page_num(notebook, child) recovers its page index directly,
;; no gtk_widget_get_parent unwrap step needed (confirmed against
;; gtk/gtknotebook.h). insert_page's `position` clamps out-of-range to
;; append (confirmed via its C body: `if (position < 0 || position >
;; nchildren) position = nchildren`), same safe-clamping convention
;; :list-box/:flow-box already rely on. tab_label is nullable (confirmed:
;; `g_return_val_if_fail (tab_label == NULL || GTK_IS_WIDGET (tab_label),
;; -1)`) — GTK auto-generates a default numbered tab when NULL; v1 always
;; passes NULL, deferring custom tab labels to a future round rather than
;; inventing a new per-child hiccup convention for a second widget-per-page.
;;
;; "switch-page" needs its OWN dispatch, not the shared value-fn/dispatch!
;; path every other value-bearing signal here uses — see
;; glitter.gtk/set-event-handler's own comment for why: confirmed against
;; gtk/gtknotebook.c that gtk_notebook_switch_page (the function that
;; EMITS "switch-page") only READS notebook->cur_page, and the actual
;; `cur_page = page` assignment happens in gtk_notebook_real_switch_page
;; — the signal's OWN DEFAULT CLASS HANDLER, confirmed registered
;; G_SIGNAL_RUN_LAST, meaning it runs AFTER user-connected handlers like
;; glitter's. Re-reading gtk_notebook_get_current_page() the way every
;; other signal here re-reads its property AFTER the signal fires would
;; read the STALE previous page, not the new one — the opposite of every
;; other value-bearing signal in this project. get-current-page/
;; set-current-page exist only for the suppressing-guard setter
;; (set-notebook-current-page!) and smoke verification.
(ffi/defcfn gtk-notebook-new              "gtk_notebook_new"              [] :pointer)
(ffi/defcfn gtk-notebook-append-page      "gtk_notebook_append_page"      [:pointer :pointer :pointer] :int)
(ffi/defcfn gtk-notebook-insert-page      "gtk_notebook_insert_page"      [:pointer :pointer :pointer :int] :int)
(ffi/defcfn gtk-notebook-remove-page      "gtk_notebook_remove_page"      [:pointer :int] :void)
(ffi/defcfn gtk-notebook-page-num         "gtk_notebook_page_num"         [:pointer :pointer] :int)
(ffi/defcfn gtk-notebook-set-current-page "gtk_notebook_set_current_page" [:pointer :int] :void)
(ffi/defcfn gtk-notebook-get-current-page "gtk_notebook_get_current_page" [:pointer] :int)

;; --- scale button (a popup slider button, e.g. a volume control) -------------
;; gtk_scale_button_new's 4th arg is a NULL-terminated array of icon names
;; shown at different value ranges — passing jolt.ffi/null lets GTK fall
;; back to its own default icon set, verified live rather than assumed.
;; "value-changed" is confirmed via gtk/gtkscalebutton.c's g_signal_new to
;; be G_TYPE_NONE, 1, G_TYPE_DOUBLE — the value travels as the signal's
;; OWN raw double argument, not something re-read via a getter afterward.
;; This is a genuinely new callable shape: 3 args like "state-set"'s, but
;; a :double in the middle slot instead of :int — needs its own literal
;; foreign-callable branch, same "every distinct shape needs its own
;; literal call site" rule as every other non-default signal here.
;; "value-changed" is also the exact same GTK signal NAME :scale/
;; :spin-button already use — signal-value's [:scale-button
;; "value-changed"] entry is added from the start this round (the
;; :password-entry near-miss lesson, applied proactively rather than
;; discovered live a third time).
(ffi/defcfn gtk-scale-button-new       "gtk_scale_button_new"       [:double :double :double :pointer] :pointer)
(ffi/defcfn gtk-scale-button-set-value "gtk_scale_button_set_value" [:pointer :double] :void)
(ffi/defcfn gtk-scale-button-get-value "gtk_scale_button_get_value" [:pointer] :double)
;; get-adjustment + adjustment-configure added round 11, fixing a real
;; pre-existing bug: :ctor used (:min p)/(:max p)/(:step p) to build the
;; initial range, but :apply never re-applied them on a later render (or,
;; per the ctor/apply prop-flow finding above, even on the FIRST render —
;; :ctor's props are always empty at the real call site), so a
;; scale-button's range silently stayed the ctor fallback (0-100 step 1)
;; regardless of what hiccup specified. Unlike :scale/:spin-button (which
;; have their own direct set-range/set-increments calls), GtkScaleButton's
;; only re-range path is through its own GtkAdjustment object directly —
;; confirmed via gtk_scale_button_get_adjustment existing and
;; gtk_adjustment_configure(adj, value, lower, upper, step_increment,
;; page_increment, page_size) being the one-call way to reconfigure it.
(ffi/defcfn gtk-scale-button-get-adjustment "gtk_scale_button_get_adjustment" [:pointer] :pointer)
(ffi/defcfn gtk-adjustment-configure "gtk_adjustment_configure"
  [:pointer :double :double :double :double :double :double] :void)
;; get-lower/get-upper/get-step-increment read the adjustment's CURRENT
;; values as fallbacks when reconfiguring — required because set-attribute
;; delivers exactly one changed key per call (see the ctor/apply prop-flow
;; note above :checkbutton-spec): reconfiguring on a lone :min change with
;; hardcoded 0/100/1 fallbacks for :max/:step would silently clobber
;; whichever dimension isn't present in THAT call, found live while fixing
;; this exact bug.
(ffi/defcfn gtk-adjustment-get-lower "gtk_adjustment_get_lower" [:pointer] :double)
(ffi/defcfn gtk-adjustment-get-upper "gtk_adjustment_get_upper" [:pointer] :double)
(ffi/defcfn gtk-adjustment-get-step-increment "gtk_adjustment_get_step_increment" [:pointer] :double)

;; --- inscription (a lighter-weight, no-markup text display than :label) ------
;; No signal at all (confirmed: no g_signal_new in gtk/gtkinscription.c) —
;; display-only, same shape as :spinner/:progress-bar/:image/:picture. v1
;; scope: :text + :text-overflow only; xalign/yalign/min-chars/nat-chars/
;; wrap-mode all deferred (GTK 4.8+ API — installed brew gtk4 is 4.22, so
;; no version gate needed here).
(ffi/defcfn gtk-inscription-new               "gtk_inscription_new"               [:string] :pointer)
(ffi/defcfn gtk-inscription-get-text          "gtk_inscription_get_text"          [:pointer] :string)
(ffi/defcfn gtk-inscription-set-text          "gtk_inscription_set_text"          [:pointer :string] :void)
(ffi/defcfn gtk-inscription-get-text-overflow "gtk_inscription_get_text_overflow" [:pointer] :int)
(ffi/defcfn gtk-inscription-set-text-overflow "gtk_inscription_set_text_overflow" [:pointer :int] :void)

;; --- search bar (a revealer-purpose-built-for-search-UI single-child container)
;; No signal of its own (confirmed: no g_signal_new in gtk/gtksearchbar.c) —
;; :search-mode driven entirely by a props-set boolean, same
;; props-driven/single-child strategy as :revealer. v1 skips
;; gtk_search_bar_connect_entry/set_key_capture_widget — both need a raw
;; GtkEditable/GtkWidget pointer glitter has no hiccup-level convention for
;; passing sideways yet; :search-mode can still be toggled programmatically
;; without them.
(ffi/defcfn gtk-search-bar-new                   "gtk_search_bar_new"                   [] :pointer)
(ffi/defcfn gtk-search-bar-set-child              "gtk_search_bar_set_child"              [:pointer :pointer] :void)
(ffi/defcfn gtk-search-bar-get-child              "gtk_search_bar_get_child"              [:pointer] :pointer)
(ffi/defcfn gtk-search-bar-set-search-mode        "gtk_search_bar_set_search_mode"        [:pointer :int] :void)
(ffi/defcfn gtk-search-bar-get-search-mode        "gtk_search_bar_get_search_mode"        [:pointer] :int)
(ffi/defcfn gtk-search-bar-set-show-close-button  "gtk_search_bar_set_show_close_button"  [:pointer :int] :void)
(ffi/defcfn gtk-search-bar-get-show-close-button  "gtk_search_bar_get_show_close_button"  [:pointer] :int)

;; --- header bar (window titlebar container — a new hybrid slot shape) --------
;; Confirmed by reading gtk_header_bar_pack's C body directly: pack_start
;; calls gtk_box_append (safe, matches hiccup order), but pack_end calls
;; gtk_box_prepend — a genuinely surprising internal detail that would
;; silently REVERSE hiccup order if glitter fed pack_end children one at a
;; time in the reconciler's normal append order. v1 therefore only wires
;; pack_start (see glitter.widget's header-bar-append-child! for the full
;; reasoning) — gtk_header_bar_pack_end is deliberately NOT bound here; a
;; future round adding it must also solve the reversal, not just call it.
;; No signal of its own. gtk_header_bar_remove handles removing EITHER a
;; pack_start child OR the title-widget (confirmed via its C body: it
;; branches on the child's actual GTK parent — start_box vs. center_box —
;; so one binding covers both cases glitter needs).
(ffi/defcfn gtk-header-bar-new                    "gtk_header_bar_new"                    [] :pointer)
(ffi/defcfn gtk-header-bar-set-title-widget       "gtk_header_bar_set_title_widget"       [:pointer :pointer] :void)
(ffi/defcfn gtk-header-bar-get-title-widget       "gtk_header_bar_get_title_widget"       [:pointer] :pointer)
(ffi/defcfn gtk-header-bar-pack-start             "gtk_header_bar_pack_start"             [:pointer :pointer] :void)
(ffi/defcfn gtk-header-bar-remove                 "gtk_header_bar_remove"                 [:pointer :pointer] :void)
(ffi/defcfn gtk-header-bar-set-show-title-buttons "gtk_header_bar_set_show_title_buttons" [:pointer :int] :void)
(ffi/defcfn gtk-header-bar-get-show-title-buttons "gtk_header_bar_get_show_title_buttons" [:pointer] :int)

;; --- action bar (a :header-bar sibling — same hybrid shape, same reversal) ---
;; gtk_action_bar_pack_end is confirmed via its C body to be
;; gtk_box_insert_child_after(action_bar->end_box, child, NULL) — inserting
;; with a NULL sibling means "insert as the FIRST child" (this project's own
;; established insert-before convention), i.e. a PREPEND under a different
;; name — the exact same reversal risk as :header-bar's pack_end, confirmed
;; independently rather than assumed to carry over just because the widgets
;; look similar. Same v1 call: only pack_start is bound/used.
;; gtk_action_bar_set_revealed/get_revealed is new, not shared with
;; :header-bar — ActionBar's internal structure is genuinely
;; revealer-wrapped (confirmed: gtk_revealer_set_child(self->revealer,
;; self->center_box) in its class_init), so this is real API, not a guess.
(ffi/defcfn gtk-action-bar-new              "gtk_action_bar_new"              [] :pointer)
(ffi/defcfn gtk-action-bar-pack-start       "gtk_action_bar_pack_start"       [:pointer :pointer] :void)
(ffi/defcfn gtk-action-bar-set-center-widget "gtk_action_bar_set_center_widget" [:pointer :pointer] :void)
(ffi/defcfn gtk-action-bar-get-center-widget "gtk_action_bar_get_center_widget" [:pointer] :pointer)
(ffi/defcfn gtk-action-bar-remove           "gtk_action_bar_remove"           [:pointer :pointer] :void)
(ffi/defcfn gtk-action-bar-set-revealed     "gtk_action_bar_set_revealed"     [:pointer :int] :void)
(ffi/defcfn gtk-action-bar-get-revealed     "gtk_action_bar_get_revealed"     [:pointer] :int)

;; --- menu button + popover (a popup surface, not a normal tree child) --------
;; The first genuinely new RELATIONSHIP in this project: a :menu-button's
;; hiccup child (if present) is expected to be a :popover, attached via
;; gtk_menu_button_set_popover — NOT a normal append-child!-managed tree
;; child the way every other single-child container here works. Confirmed
;; by reading gtk_menu_button_set_popover's C body directly that it DOES
;; genuinely parent the popover (gtk_widget_set_parent/unparent), so this
;; is real ownership, not a passive reference. "activate" is confirmed via
;; gtk/gtkmenubutton.c's g_signal_new to be the plain G_TYPE_NONE, 0
;; shape — already registered as :on-activate in glitter.widget/signals
;; (originally added speculatively, first real use here) — free reuse,
;; no new callable branch. GtkPopover's "closed" is likewise confirmed
;; G_TYPE_NONE, 0 — also a free reuse, needing only a new signal NAME
;; entry (:on-closed), not a new shape.
;;
;; Confirmed via gtkpopover.c that gtk_popover_popdown's underlying
;; gtk_popover_hide vfunc emits "closed" SYNCHRONOUSLY as part of the same
;; call (_gtk_widget_set_visible_flag -> gtk_widget_unmap ->
;; g_signal_emit(CLOSED)) — same "synchronous emission, suppress it during
;; a programmatic setter" shape as every other value-bearing widget here.
;; gtk_popover_popdown itself early-returns when already invisible, so a
;; redundant call is already a safe no-op even before glitter's own
;; suppressing-guard equality check.
(ffi/defcfn gtk-menu-button-new           "gtk_menu_button_new"           [] :pointer)
(ffi/defcfn gtk-menu-button-set-label     "gtk_menu_button_set_label"     [:pointer :string] :void)
(ffi/defcfn gtk-menu-button-get-label     "gtk_menu_button_get_label"     [:pointer] :string)
(ffi/defcfn gtk-menu-button-set-popover   "gtk_menu_button_set_popover"   [:pointer :pointer] :void)
(ffi/defcfn gtk-menu-button-get-popover   "gtk_menu_button_get_popover"   [:pointer] :pointer)
(ffi/defcfn gtk-popover-new               "gtk_popover_new"               [] :pointer)
(ffi/defcfn gtk-popover-set-child         "gtk_popover_set_child"         [:pointer :pointer] :void)
(ffi/defcfn gtk-popover-get-child         "gtk_popover_get_child"         [:pointer] :pointer)
(ffi/defcfn gtk-popover-set-has-arrow     "gtk_popover_set_has_arrow"     [:pointer :int] :void)
(ffi/defcfn gtk-popover-get-has-arrow     "gtk_popover_get_has_arrow"     [:pointer] :int)
(ffi/defcfn gtk-popover-set-autohide      "gtk_popover_set_autohide"      [:pointer :int] :void)
(ffi/defcfn gtk-popover-get-autohide      "gtk_popover_get_autohide"      [:pointer] :int)
(ffi/defcfn gtk-popover-popup             "gtk_popover_popup"             [:pointer] :void)
(ffi/defcfn gtk-popover-popdown           "gtk_popover_popdown"           [:pointer] :void)

;; --- window handle (a CSD drag-handle single-child container) ----------------
;; No signal (confirmed: no g_signal_new in gtk/gtkwindowhandle.c) — rounds out
;; the simple single-child wrapper family (:frame/:revealer/:expander/...).
(ffi/defcfn gtk-window-handle-new       "gtk_window_handle_new"       [] :pointer)
(ffi/defcfn gtk-window-handle-set-child "gtk_window_handle_set_child" [:pointer :pointer] :void)
(ffi/defcfn gtk-window-handle-get-child "gtk_window_handle_get_child" [:pointer] :pointer)

;; --- stack (a :notebook sibling with no tabs of its own) ----------------------
;; gtk_stack_add_child (unnamed) / gtk_stack_add_named both delegate to the
;; same internal gtk_stack_add_internal — confirmed via its C body that GTK
;; auto-selects the first added VISIBLE child as visible-child (a THIRD
;; instance of the exact :notebook round-9 finding: `if (priv->visible_child
;; == NULL && gtk_widget_get_visible (child_info->widget)) set_visible_child
;; (...)`), so mounting a :stack with initial children genuinely dispatches a
;; mount-time "notify::visible-child-name". gtk_stack_remove takes the child
;; widget directly, no name needed, so :stack fits the existing generic
;; remove-child! dispatch with no glitter.gtk-level special-casing.
;; visible-child-name is a real GObject property (g_param_spec_string), so
;; "notify::visible-child-name" is a free reuse of the 3-arg-void shape
;; already generalized for :expander/:paned/:list-box — no new
;; set-event-handler branch needed, same as :menu-button's/:popover's round-10
;; signals.
(ffi/defcfn gtk-stack-new                   "gtk_stack_new"                   [] :pointer)
(ffi/defcfn gtk-stack-add-child             "gtk_stack_add_child"             [:pointer :pointer] :pointer)
(ffi/defcfn gtk-stack-add-named             "gtk_stack_add_named"             [:pointer :pointer :string] :pointer)
(ffi/defcfn gtk-stack-remove                "gtk_stack_remove"                [:pointer :pointer] :void)
;; get-child-by-name guards set-stack-visible-child-name! against a real,
;; found-live GTK warning: :apply runs at create! time, BEFORE the
;; reconciler has appended any children (see the ctor/apply prop-flow
;; note in glitter.widget) — so an initial :visible-child-name landed on
;; a completely empty stack, and gtk_stack_set_visible_child_name warned
;; "Child name '<name>' not found in GtkStack" every time, silently
;; falling back to GTK's own auto-select-first-page behavior regardless
;; of what name was requested. Checking the page exists FIRST (this
;; getter returns NULL until the reconciler's later append-child calls
;; have actually added it) skips the doomed initial attempt cleanly; the
;; identical call on a LATER re-render, once real pages exist, finds the
;; page and proceeds normally.
(ffi/defcfn gtk-stack-get-child-by-name     "gtk_stack_get_child_by_name"     [:pointer :string] :pointer)
(ffi/defcfn gtk-stack-set-visible-child-name "gtk_stack_set_visible_child_name" [:pointer :string] :void)
(ffi/defcfn gtk-stack-get-visible-child-name "gtk_stack_get_visible_child_name" [:pointer] :string)

;; --- drop down (the first "choose from options" widget) ----------------------
;; gtk_drop_down_new_from_strings takes a raw C string array — this project's
;; FFI layer has never needed to marshal an array argument, so v1 sidesteps
;; it entirely: build a GtkStringList incrementally (gtk_string_list_new(NULL)
;; + gtk_string_list_append per item, same one-call-per-item shape as every
;; other collection here), then pass it as gtk_drop_down_new's `model`
;; (GListModel*) — a GtkStringList* is usable directly as a GListModel*
;; pointer, no cast needed at the raw-pointer FFI level. `expression`
;; (confirmed nullable via gtk_drop_down_new's own g_return_if_fail) is
;; jolt.ffi/null — GtkStringList items already display as plain strings with
;; no custom expression needed. "selected" is a real GObject property
;; (guint), so "notify::selected" is another free reuse of the 3-arg-void
;; shape. "activate" is confirmed via gtk/gtkdropdown.c's own g_signal_new
;; call to be the exact G_TYPE_NONE, 0 shape :menu-button's already
;; established — the SAME :on-activate signals entry, a second real use.
;; gtk_drop_down_set_model exists (confirmed in gtk/gtkdropdown.h) so :items
;; is entirely an :apply-time concern — the ctor always builds with an EMPTY
;; string list (matching the ctor/apply prop-flow finding above: :ctor's
;; props are always empty at the real call site anyway, so there's no
;; "smarter" ctor-time construction to attempt here even if GTK allowed it).
(ffi/defcfn gtk-string-list-new     "gtk_string_list_new"     [:pointer] :pointer)
(ffi/defcfn gtk-string-list-append  "gtk_string_list_append"  [:pointer :string] :void)
(ffi/defcfn gtk-drop-down-new       "gtk_drop_down_new"       [:pointer :pointer] :pointer)
(ffi/defcfn gtk-drop-down-set-model "gtk_drop_down_set_model" [:pointer :pointer] :void)
(ffi/defcfn gtk-drop-down-set-selected "gtk_drop_down_set_selected" [:pointer :uint] :void)
(ffi/defcfn gtk-drop-down-get-selected "gtk_drop_down_get_selected" [:pointer] :uint)

;; --- grid (genuinely new 2D per-child positioning) ----------------------------
;; gtk_grid_attach(grid, child, column, row, width, height) needs 4 ints PER
;; CHILD — the first container here whose placement data lives on the CHILD's
;; own hiccup props, not a fixed slot or append order. glitter.gtk/set-attribute
;; special-cases :grid/column|row|column-span|row-span, stashing them on the
;; child's own tracking atom instead of routing through apply-props! (verified
;; empirically, not assumed, that create-element never receives a child's real
;; hiccup props at all — only set-attribute does, called once per key, before
;; the child is ever appended to its parent). gtk_grid_remove takes the child
;; widget directly, no position needed, so removal fits the existing generic
;; dispatch. No signal of its own. See glitter.widget's grid-attach! and
;; glitter.gtk's append-child/insert-before/replace-child for the full
;; threading story.
(ffi/defcfn gtk-grid-new                "gtk_grid_new"                [] :pointer)
(ffi/defcfn gtk-grid-attach              "gtk_grid_attach"              [:pointer :pointer :int :int :int :int] :void)
(ffi/defcfn gtk-grid-remove              "gtk_grid_remove"              [:pointer :pointer] :void)
(ffi/defcfn gtk-grid-get-child-at        "gtk_grid_get_child_at"        [:pointer :int :int] :pointer)
(ffi/defcfn gtk-grid-set-row-spacing     "gtk_grid_set_row_spacing"     [:pointer :uint] :void)
(ffi/defcfn gtk-grid-get-row-spacing     "gtk_grid_get_row_spacing"     [:pointer] :uint)
(ffi/defcfn gtk-grid-set-column-spacing  "gtk_grid_set_column_spacing"  [:pointer :uint] :void)
(ffi/defcfn gtk-grid-get-column-spacing  "gtk_grid_get_column_spacing"  [:pointer] :uint)

;; --- signals & reference counting (libgobject) -------------------------------
;; g_signal_connect_data(instance, detailed_signal, c_handler, data, destroy_data, flags)
;; Returns the handler id (a gulong). destroy_data is a GClosureNotify fn ptr —
;; pass ffi/null. c_handler is the foreign-callable pointer.
(ffi/defcfn g-signal-connect-data "g_signal_connect_data"
  [:pointer :string :pointer :pointer :pointer :uint] :uint64)
(ffi/defcfn g-signal-handler-disconnect "g_signal_handler_disconnect" [:pointer :uint64] :void)

(ffi/defcfn g-object-ref-sink "g_object_ref_sink" [:pointer] :pointer)
(ffi/defcfn g-object-unref    "g_object_unref"    [:pointer] :void)
