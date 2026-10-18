# HoneyPot UI Design Principles Guide
*Curated Design Standards, Material 3 + Google Contacts Hybrid Aesthetics, and Component Rules*

---

## 1. High-Level Philosophy: The Naive User First

1. **Human, Not Technical**:
   - The end user is an everyday person who wants reassurance, safety, and simplicity.
   - **Rule**: Never expose machine learning pipeline details, BERT architecture descriptions, on-device vector benchmarks, or raw technical statistics on primary dashboard surfaces.
   - **Placement**: All technical architecture, security specs, and diagnostic logs belong strictly in **Settings &rarr; Technical Architecture / About** dialogs for power users and evaluators.

2. **Confidence Through Restraint**:
   - A single clear statement like **"You are protected"** inspires more confidence than a dozen defensive disclaimers.
   - Avoid redundant status labels (*"Honeypot is actively monitoring..."*, *"No threats right now"*, *"100% on-device privacy guaranteed"*). Clean visual calm proves stability.

---

## 2. Layout & Structure: Google Contacts + Material 3 Hybrid

1. **Unbox the UI (Avoid Pill & Rounded-Box Overload)**:
   - **Anti-Pattern**: Wrapping every snippet of text, sub-header, and status into its own bordered card or rounded badge. This creates visual noise and feels amateurish.
   - **Best Practice**: Use open, breathable typography with natural vertical hierarchy. 

2. **Surface Grouping & Dividers**:
   - Group related rows into single cohesive Material 3 surface containers (similar to Google Contacts list items).
   - Inset dividers (`android:layout_marginStart="54dp"` or `"70dp"`) aligned with avatar baselines to preserve clean visual separation without harsh box borders.

3. **Typography-First Header**:
   - No excessive hero emblems, spinning badges, or animated shield halos floating above the main headline.
   - Let the bold headline (**26sp bold, letterSpacing -0.01**) anchor the top of the screen naturally.

---

## 3. Color Hierarchy & Palette Rules

| Element | Light Mode (`values/colors.xml`) | Dark Mode (`values-night/colors.xml`) | Rationale |
| :--- | :--- | :--- | :--- |
| **Primary Text** | `#202124` *(Material 3 Charcoal)* | `#F1F5F9` *(Soft Pearl)* | **Never use pure black (`#000000`)**. Charcoal prevents harsh eye fatigue. |
| **Secondary Text** | `#5F6368` *(Balanced Neutral)* | `#94A3B8` *(Slate-400)* | Clear readability with proper contrast against surfaces. |
| **Active Nav / Blue Icons** | `#2563EB` *(Vibrant Royal)* | `#38BDF8` *(Luminous Azure)* | **Never dim icons.** Keep rich, prominent, saturated hues. |
| **Warning / Red Alert** | `#DC2626` *(Vivid Danger)* | `#EF4444` *(Vivid Crimson)* | Instantly noticeable; avoid washed-out pastel pinks (`#F28B82`). |
| **Unselected Nav Icons** | `#475569` *(Slate-600)* | `#94A3B8` *(Slate-400)* | High-contrast, sharp, and crisp; never faded to near-invisibility. |
| **Nav Active Indicator** | `#D3E3FD` *(Soft Google Pill)* | `#004A77` *(Deep Blue Pill)* | Clean Material 3 pill container behind active icon. |

### The "No-Dimmed-Icons" Law
- When refining text colors to avoid pure black, **never dim or desaturate icons**.
- Keep navigation item icon selector (`bottom_nav_item.xml`) and text selector (`bottom_nav_text.xml`) completely separate.
- Icons should never be tinted with faded pastel colors or murky navy shades against light surfaces.

---

## 4. Message & List Item Standards (Google Messages Paradigm)

1. **Clean Contact/Sender Avatars**:
   - Use clean, circular 40dp avatar containers with single bold initials:
     - Safe contact/service: Initial (e.g. **"S"** for State Bank, **"H"** for HDFC) in vibrant blue (`#2563EB` / `#38BDF8`).
     - Flagged scam message: Initial (e.g. **"E"** for Electricity Alert) in alert red (`#DC2626` / `#EF4444`) on a soft tinted circle (`bg_m3_avatar_alert`).
   - **Strict Rule**: **No caution emojis (`⚠️`) inside circular avatars**. Text emojis suffer from font ascent/descent metrics that cause off-center placement and device font inconsistencies.
   - **No Unnecessary Warning Signs**: Visual distinction is achieved through subtle red-tinted surface backing (`bg_m3_alert`) and alert text colors—not alarmist caution triangles.

2. **Standardized Date Formats**:
   - **Today**: Show `"Today"`
   - **Within the current week**: Show abbreviated day name (e.g. `"Thu"`, `"Mon"`)
   - **Earlier than current week**: Show calendar date formatted as `"dd/mm/yy"` (e.g. `"12/10/26"`)

3. **Message Body Truncation**:
   - Max 1 line snippet with `android:singleLine="true"` and `android:ellipsize="end"`.

---

## 5. Quick Actions & Buttons

1. **Text-First, Clean Targets**:
   - Use direct, action-oriented verbs without emoji prefixes:
     - &check; `"Scan a screenshot"` &nbsp;&nbsp;|&nbsp;&nbsp; &cross; `"📸 Scan a screenshot"`
     - &check; `"Report to police"` &nbsp;&nbsp;|&nbsp;&nbsp; &cross; `"🚨 Report to police"`
2. **Ergonomic Touch Targets**:
   - Equal weight (`layout_weight="1"`), minimum `48dp` touch height, 14dp vertical padding.
   - Neutral surface container backing that seamlessly responds to mode toggling.

---

## 6. Motion & Animations Policy

1. **Calm Main Dashboard**:
   - The primary status screen should remain static and battery-efficient (0 idle CPU usage).
   - Continuous looping animations (breathing halos, pulsating rings) distract naive users and cause perceived device overhead.
2. **Purposeful Contextual Motion Only**:
   - Restrict animations to active operations:
     - The radar sweep during an active screenshot scan.
     - Bottom sheet slide-up transitions.
     - Smooth color-interpolated mode toggles.
