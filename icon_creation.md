# HoneyPot App Icon Design Specification & Prompt Guide

This document provides complete contextual, aesthetic, and technical specifications for generating the official app icon for **HoneyPot** using Stitch or AI design generation tools.

---

## 1. App Identity & Essence

* **App Name:** HoneyPot (or *HoneyPot AI*)
* **Tagline:** Autonomous Scam Defense & Active Honeypot Trap
* **Core Function:** 
  An on-device mobile security app that defends everyday smartphone users from SMS phishing, WhatsApp scams, financial fraud, and malicious APK lures.
* **The Unique Metaphor:** 
  In cybersecurity, a **"honeypot"** is an irresistible decoy trap that lures attackers, safely engages them with realistic conversational personas, and extracts actionable evidence (UPI IDs, bank details, phishing links) without letting them harm the user.
* **Tone & Feeling:** 
  **Reassuring, intelligent, modern, and trustworthy.** It balances the warmth of a guardian with the precision of a high-tech on-device neural security engine.

---

## 2. Visual Symbolism & Motifs

The icon should synthesize two primary ideas:
1. **The Trap / Honey Metaphor:** 
   Hexagonal honeycomb geometry, amber/golden nectar, or a crystal-like honey vessel.
2. **The Defense & Tech Metaphor:** 
   Security shield, neural node, protective aura, or scanning radar.

### Symbolism Elements to Combine:
* **Hexagon / Honeycomb Cell:** Strong structural integrity, nature's most efficient storage, represents the "honeypot" enclosure.
* **Shield / Emblem Silhouette:** Universal symbol of safety, defense, and privacy.
* **Inner Core / Luminous Pulse:** Represents on-device intelligence (local BERT / AI inference) actively analyzing threats.
* **Clean Silhouette:** Must remain instantly recognizable at micro sizes (48×48dp on an Android launcher grid).

---

## 3. Design Aesthetics & Style Guidelines

* **Design Language:** Google Material 3 (Material You) meets modern premium iOS/Android flagship aesthetics.
* **Visual Style:** 
  * Clean, geometric, and tactile with soft depth (modern frosted glassmorphism or smooth claymorphism).
  * Subtle ambient shadows, crisp vector boundaries, and gentle rim lighting.
  * **Avoid:** Cluttered clip-art, cheesy cartoon bees, literal padlocks with keyholes, flat boring clip art, or overly aggressive "hacker" skull/matrix cliches.

### Color Palette

| Role | Color Name | Hex Code | Visual Effect |
| :--- | :--- | :--- | :--- |
| **Primary Shield / Tech** | Google Royal Blue | `#0B57D0` / `#1A73E8` | Trustworthy, authoritative, Google-native security |
| **Accent / Intelligence** | Azure Sky Blue | `#60A5FA` / `#A8C7FA` | Luminous edge highlight, modern tech glow |
| **The "Honey" Trap** | Warm Amber Glow | `#F59E0B` / `#D97706` | Subtle golden warmth indicating the "honeypot" lure |
| **Dark Base / Obsidian** | Deep Midnight Navy | `#0B132B` / `#0F172A` | Premium depth, high contrast background |
| **Light Surface** | Soft Pearl / Canvas | `#F2F4F9` / `#FFFFFF` | Clean, breathable surface for light adaptive containers |

---

## 4. Concept Directions for Stitch Generation

### Concept A: The Hexagonal Cyber-Shield (Recommended)
> A sleek, beveled shield formed from a central golden-amber honeycomb cell suspended inside a deep royal blue outer shield. Soft ambient rim lighting highlights the beveled edges. The center features a clean glowing dot or subtle droplet of golden intelligence.

### Concept B: The Minimalist Honeycomb Radar
> A clean, modern hexagonal honeycomb grid where the central node pulses with a subtle warm honey gradient, surrounded by concentric protective blue rings. Minimalist, premium, and instantly readable at 32px.

### Concept C: The Monogram "H" Honey-Vault
> A bold, modern letter "H" engineered from two interlocking geometric pillars with an amber-glowing shield cavity in the center, resting on a rich obsidian-blue squircle container.

---

## 5. Technical Icon Parameters

* **Canvas Size:** 1024 × 1024 px (Master icon export)
* **Shape / Safe Zone:** 
  * Fits within an Android squircle / circle container (keep key artwork within the central 66% diameter safe zone).
  * Layerable structure: Foreground element with transparent surroundings + optional background surface.
* **Lighting:** Soft 45° top-left studio key light, subtle specular highlight on upper bevel, soft ambient occlusion shadow below.

---

## 6. Ready-to-Use Generation Prompts for Stitch

### Prompt 1: Premium 3D Beveled Shield (Flagship Style)
```text
App icon design for a mobile cybersecurity app named "HoneyPot", modern Material 3 aesthetic, a sleek beveled geometric shield integrated with a glowing hexagonal honeycomb core, elegant fusion of vibrant Google royal blue (#1A73E8) and a subtle warm golden amber glow (#F59E0B) in the center, smooth matte frosted finish with soft ambient lighting, clean centered composition on a dark slate navy squircle background (#0F172A), premium, minimal, tech, vector-like clarity, high resolution, 8k, UI/UX masterpiece --no text, --no realistic bee, --no cartoon, --no clutter
```

### Prompt 2: Minimalist Flat / Vector Icon
```text
Minimalist app icon logo for "HoneyPot AI" security app, modern flat design with subtle gradients, abstract hexagonal shield silhouette with a stylized amber honey droplet and security radar wave, clean Google Blue (#0B57D0) and warm honey yellow palette, crisp geometry, perfectly centered, iconic, ultra-legible at small sizes, Figma vector style, white or transparent background --no text, --no padlock, --no realistic insect
```

### Prompt 3: Cyber Glassmorphism Trap
```text
App icon for mobile cyber defense app, floating translucent frosted glass shield with subtle hexagonal honeycomb facets, glowing cyan-blue edge lighting, warm amber core suspended in the center representing a honeypot trap, clean dark navy background, Apple and Google design language, soft drop shadow, 1024x1024 app store icon --no text, --no low-res, --no complex background
```
