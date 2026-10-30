# Private local mirror of the Wallpaper Engine docs

The cloud environment this project was built in cannot reach `docs.wallpaperengine.io`. To get the full documentation as Markdown:

1. Run Claude Code **on your own computer** (it has normal internet access), in this repository.
2. Paste:

   > Open https://docs.wallpaperengine.io/en/scene/overview.html and follow the sidebar to read the scene documentation
   > (scene overview, first wallpaper tutorial, effects, parallax, masks, flow maps, scripting if present). For each page write a
   > Markdown file under `docs/wallpaper-engine/` (mirror the URL path in the file name) with the page text, tables and parameter
   > lists, and an index `docs/wallpaper-engine/INDEX.md` linking all pages. Keep the source URL at the top of every file.
   > Then update `.claude/skills/wallpaper-engine/SKILL.md`: replace every "(unverified)" with confirmed facts (effect parameters,
   > value ranges, mask / flow map usage) in your own words, and add a short per-effect cheat sheet in `references/effects.md`.

3. `docs/wallpaper-engine/` is in `.gitignore` on purpose: it is a vendor's documentation, so keep it private and do not push it to
   this public repository. The skill (our own summary) is what gets committed.
