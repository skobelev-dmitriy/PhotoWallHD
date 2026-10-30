---
name: wallpaper-engine
description: Concepts and workflow of Wallpaper Engine's 2D scene editor (layers, effects, opacity masks, flow maps, depth parallax) and how they map to PhotoWall HD's own "living photo" effects. Use when the user talks about Wallpaper Engine, animating a still photo with water/foliage/cloud effects, painting masks for effects, or porting such effects to this project.
---

# Wallpaper Engine (2D scenes) — working notes

Written from public documentation snippets and general knowledge, **in our own words**. It is *not* a copy of the official docs
(a vendor's documentation should not be republished in a public repo). Items marked **(unverified)** were not confirmed against the
original pages; check them before relying on them. Official docs: <https://docs.wallpaperengine.io/en/scene/overview.html>.

## Core idea

A 2D scene = one or more image **layers**. You add **effects** to a layer; each effect is a small GPU shader that changes how the
image is drawn over time (distort, scroll, tint, ...). An effect acts on the whole layer unless you limit it with a **mask**.

## Workflow of the "first effects" tutorial

1. Import a photo (landscape with a lake/shoreline and a tree line).
2. Add **Water Ripple** to the layer. By default it distorts the *whole* image.
3. Limit it to the water: paint an **Opacity Mask** (clear the mask to 0 first, then paint the water area). Feather the edge.
4. Fix realism: ripples look too strong far away. Fade the mask out toward the far side (lower opacity/value with distance).
5. For the tree line use **Foliage Sway**. It uses a **Flow Map** (not an opacity mask) to say where and in which direction things move;
   its **Scale** setting matches the size of the foliage in the picture.
6. Iterate on strength / scale / speed / direction until it looks natural; keep the motion subtle.

## Effects seen in the docs (as far as verified)

| Effect | Use | Notes |
|---|---|---|
| Water Ripple | still water, hot air, somewhat cloth/hair | distortion; scrollable in a direction; limited by opacity mask |
| Water Waves | water, clothes, hair | wave animation |
| Foliage Sway | trees, bushes | flow map + scale |
| Shake | breathing, vibration | docs mention simulating breathing; a forum report says a low frame rate setting makes it stutter |
| Cursor Ripple | interactive | reacts to the mouse (not relevant on a projector) |
| Depth Parallax | pseudo-3D | needs a **depth map** (can be generated or imported); needs camera parallax enabled |
| Layer parallax | per-layer depth | parallax depth 0 on a layer turns it off for that layer |

Other effects, parameters and value ranges: **(unverified / not collected)**. See `references/MIRROR-DOCS.md` to build a private local mirror.

## How it works inside (general technique — our knowledge, not from the docs)

A fragment shader samples the layer texture at `uv + offset`. The offset comes from time-varying functions (sines, noise, scrolling
normal/flow maps) multiplied by the mask. Small offsets + masks = "the picture breathes". Depth parallax shifts pixels by depth × camera offset.

## Mapping to PhotoWall HD

We reproduce the *idea* (mask + effect + parameters) with our own math: `docs/EFFECT-SPEC.md`.

| Wallpaper Engine | PhotoWall HD | Where |
|---|---|---|
| Water Ripple + opacity mask (+ fade with distance) | `ripple` + mask + `perspective` | `vision/app/effects.py`, `LiveMath.kt` |
| Foliage Sway + flow map | `sway` (base fixed, top moves) | same |
| Shake (breathing) | `breathe` | same |
| Water Waves on cloth | `flow` | same |
| Clouds | `clouds` (slow low-frequency warp) | same |
| Painting masks by hand | automatic masks from the vision service (SegFormer / classical) | `vision/` |
| Depth Parallax | not implemented | idea in `docs/EFFECTS-IDEAS.md` |

Pipeline: `docs/LIVE-PHOTOS.md`. Mesh-friendly limits (why ripples are gentle swells): `docs/EFFECT-SPEC.md`.

## Guidance when helping the user

- Keep amplitudes tiny (fractions of a percent of the width); "alive but looks still" is the goal.
- Always limit effects with masks; fade water effects toward the far side.
- Do not promise physically convincing hair/dress motion from shaders; that needs masks of the parts or image-to-video models.
- If the user supplies the real docs (local mirror in `docs/wallpaper-engine/`, git-ignored), read them first and correct this file.
