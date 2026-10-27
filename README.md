# Pagewright

A small desktop PDF editor for macOS, Windows and Linux, built with Java Swing and Apache PDFBox.

- Open a PDF, preview it, zoom and page through it
- **Edit existing text**: click a word (double-click = sentence, drag = any part of a line) and replace it
- **Insert** text, images, lines and rectangles; drag to move, handles to resize, Delete key to remove
- Rotate or delete pages, merge PDFs, split into one file per page, extract text
- Full undo / redo, and your original file is never changed unless you choose to overwrite it

## Run it

**macOS: clone the repo and double-click `Start Pagewright`** (the red icon in the repo's top folder).
Nothing needs to be installed first. The first launch finds Java on your Mac (or downloads one into the
project folder), builds the app and opens it; that takes a few minutes and needs internet. Every later click
opens it straight away.

- macOS may ask whether "Start Pagewright" can access your Desktop/Documents folder if you cloned the repo
  there. Choose Allow.
- If you download the repo as a ZIP instead of using `git clone`, macOS adds a quarantine flag. Right-click
  the launcher, choose Open, and confirm once.
- Problems? See `~/Library/Logs/Pagewright/launch.log`.

**Terminal (macOS and Linux):**

```sh
git clone https://github.com/PankajPandey-codes/pagewright
cd pagewright
./run.sh                 # sets itself up on first run, then opens the app
./run.sh some-file.pdf   # open a file straight away
```

**Windows:** install a JDK 17+ (for example from https://adoptium.net), then run `mvnw.cmd package` and
`java -jar target\pagewright.jar`. Or download a release build (below).

**Prebuilt downloads:** tagging a version (`git tag v1.0.0 && git push --tags`) makes GitHub Actions build and
attach a macOS `.dmg`, a Windows `.zip` and a Linux `.tar.gz` to the release, each with its own Java runtime,
so users need nothing installed. These builds are not code-signed, so macOS and Windows will warn on first open.

## Build a double-clickable app yourself

```sh
packaging/build-mac.sh          # -> dist/Pagewright.app (bundles its own Java runtime)
packaging/build-mac.sh dmg      # -> dist/Pagewright-1.0.0.dmg
packaging/make-launcher.sh      # rebuilds the "Start Pagewright" launcher from packaging/launcher.applescript
```

## Keyboard shortcuts

(`Cmd` on macOS, `Ctrl` elsewhere)

| Action | Shortcut |
| --- | --- |
| Open / Save / Save As | Cmd+O / Cmd+S / Shift+Cmd+S |
| Undo / Redo | Cmd+Z / Shift+Cmd+Z |
| Find and replace text | Cmd+F |
| Insert text / image / line / rectangle | Cmd+T / Shift+Cmd+I / Shift+Cmd+L / Shift+Cmd+R |
| Rotate page left / right | Cmd+L / Cmd+R |
| Previous / next page | PageUp / PageDown, or Opt+Cmd+Left / Right |
| Zoom in / out / actual size | Cmd+= / Cmd+- / Cmd+0 |
| Delete selected item, deselect | Delete or Backspace, Esc (with the page focused) |
| Nudge selected item | Arrow keys (Shift = 10x) |

## Things to know

- Inserted items stay editable until you save, or until you run a page-changing action (rotate, delete page,
  merge, replace text), which draws them into the PDF first. Undo still works across that.
- Inserted text uses the built-in Helvetica font, so Latin characters only (no rupee sign or non-Latin scripts yet).
- Text replacement edits the PDF text directly when the font allows it. For subset fonts it covers the old text
  with a white box and writes the new text on top. **That is not secure redaction**: the original text remains
  in the file.
- Scanned pages (images of text) cannot be edited as text.
- Items can't be added to a rotated page yet.

## License

MIT. See [LICENSE](LICENSE).
