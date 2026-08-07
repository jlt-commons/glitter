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
(ffi/defcfn gtk-widget-set-sensitive  "gtk_widget_set_sensitive"  [:pointer :int] :void)
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

;; --- signals & reference counting (libgobject) -------------------------------
;; g_signal_connect_data(instance, detailed_signal, c_handler, data, destroy_data, flags)
;; Returns the handler id (a gulong). destroy_data is a GClosureNotify fn ptr —
;; pass ffi/null. c_handler is the foreign-callable pointer.
(ffi/defcfn g-signal-connect-data "g_signal_connect_data"
  [:pointer :string :pointer :pointer :pointer :uint] :uint64)
(ffi/defcfn g-signal-handler-disconnect "g_signal_handler_disconnect" [:pointer :uint64] :void)

(ffi/defcfn g-object-ref-sink "g_object_ref_sink" [:pointer] :pointer)
(ffi/defcfn g-object-unref    "g_object_unref"    [:pointer] :void)
