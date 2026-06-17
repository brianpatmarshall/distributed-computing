# Scripts Reference

Documentation for the shell scripts, login files, and function library in `~/bin/copy`.

## Top-level Scripts

### bash_profile
Bash login script. Sources all function definitions from `~/bin/functions`, exports environment variables (`CDPATH`, `EDITOR`, `PATH`, `PS1`, etc.), removes duplicate `PATH` entries, optionally sources `~/.myProfile` and `~/.bashrc`, and validates `PATH`/`CDPATH` entries via `checkPath`.

### bashrc
Interactive shell rc file. Defines aliases for common variations of `cd`, `find`, `ls`, `grep`, sourcing, and `vi`'ing dotfiles. Sources `~/.myBashrc` if present.

### checkPath
Takes a colon-separated path (e.g. `$PATH`) and reports which of its directories exist and which do not. Useful when login scripts are ported between hosts with different directory layouts.

### cref
Create-reference/function scaffolding script. Accepts `-d <description>` and `-u <usage>` plus a function name, prompts on collisions, then writes a skeleton bash function file with a banner comment block.

### cres
Create-script scaffolder. Based on a `-t` type flag (awk, bash, scala, groovy, jython, jshell, ksh, node, perl, ruby, R, sed, typescript, python, zsh, ...), generates a new executable script with the correct shebang, comment character, and banner header, then drops the user into `$EDITOR` at the last line.

### findClass
Searches a directory tree for a named Java class inside `.class` files and `.jar` archives. Caches results under `~/classCatalog`, displays matches with highlighting, and offers to skip re-scanning when cached results exist.

### findDups
Runs `md5sum` on every file in the current directory and prints files whose checksums collide — i.e. files with different names but identical content.

### myBashrc
Personal aliases file (sourced from `bashrc`). Contains shortcuts for sensors, `vi`'ing personal dotfiles (todo lists, favorite colors, addresses, recipes, udemy class lists), Docker (`dc`, `di`, `dn`, ...), Git (`gdno`), Neo4j, Java (`sjh`, `jv`), SSH targets, and color scripts (`src`, `srccc`, `srccmd`, `srcfg`, `srcbg`). Runs `srccc` at end to pick a random color combo.

### myProfile
Personal environment-variable file. Exports `JAVA7_HOME` ... `JAVA24_HOME` paths and sets the active `JAVA_HOME` to `JAVA21_HOME`.

### setJavaHome
Switches `JAVA_HOME` to `/opt/java/<version>` (or `/e/opt/java/<version>` off-Linux), prompting for the version if not supplied, and prepends the chosen JDK's `bin` to `PATH` with duplicates removed. Also defines a local `removeDups` helper.

### setupSSH
Bootstraps passwordless SSH to a list of hosts. Creates `~/.ssh` locally, generates `dsa`/`rsa`/`rsa1` keys when missing, then over SSH creates `~/.ssh` on each remote host and appends the public keys to `authorized_keys` with mode `600`.

### Unix_Shell_Scripting.pdf
Reference PDF (not a script).

---

## Directory: `color/`

Random and interactive terminal color schemes. These scripts rely on favorites files under `~/.hidden/` (`.favoriteBGs`, `.favoriteFGs`, `.favoriteCombos`) and on helper functions like `colorBG` and `colorPrompt` from `functions/`.

### color/colorAllBG
Walks every RGB combination (with optional `-r`, `-g`, `-b` starting values) and applies it as a background via the OSC `]11;rgb:` escape. Uses `timeRead` to briefly wait for a keypress; if pressed, saves the triple to `favColors` and pauses.

### color/colorChooseBG
Picks a random background from `~/.hidden/.favoriteBGs` (or uses supplied args) and applies it via `colorBG`.

### color/colorChooseFG
Picks a random foreground from `~/.hidden/.favoriteFGs` and applies it via `colorPrompt`.

### color/colorRandom
Defines inline `colorClear`, `colorToken`, and `colorPrompt` helpers, then picks a random bg and fg combo from the favorites files and applies both.

### color/colorRandomBG
Picks a random background from `~/.hidden/.favoriteBGs` and applies it.

### color/colorRandomCombo
Picks a random combo/background/foreground from the favorites files, prints the command in a box, and evaluates `colorBG` + `colorPrompt`.

### color/colorRandomFG
Defines inline `colorClear`, `colorToken`, and `colorPrompt` helpers, then picks a random foreground and applies it.

### color/favColors
Data file of "favorite" RGB triples accumulated by `colorAllBG`.

---

## Directory: `functions/`

Shell-function library sourced and exported by `bash_profile`. Each file defines one (or a few) related functions.

### Text-attribute helpers
- **bold** — `tput bold`; turn bold fonts on.
- **boldOff** — `tput sgr0`; turn bold and all attributes off.
- **hlt** — `tput smso`; turn highlighting (standout) on.
- **hltOff** — `tput rmso`; turn highlighting off.
- **hlp** — Filter that wraps every match of a regex in highlight-on/highlight-off via `sed`; accepts files or STDIN.

### Layout / formatting
- **box** — Wrap arguments (or STDIN) in a `#===...` box for easy visual scanning.
- **sws** — Squeeze-whitespace filter; reads from STDIN and emits the stream with duplicate whitespace collapsed.

### Log-style messages
- **info** — Print `[INFO] ...` message (cyan label).
- **ok** — Print `[OK] ...` message (green label).
- **warn** — Print `[WARN] ...` message (yellow label).
- **error** — Print `[ERROR] ...` message (red label).
- **die** — Print an error message via `error` and `exit 1`.

### Case conversion
- **upper** — Uppercase args or STDIN via `tr`.
- **lower** — Lowercase args or STDIN via `tr`.
- **initCap** — Uppercase the first character of each word (args or STDIN).
- **initLower** — Lowercase the first character of each word (args or STDIN); also bundles a `lower` helper.

### Quoting helpers
- **q** — Single-quote args or each line of STDIN.
- **qq** — Double-quote args or each line of STDIN.
- **qw** — Double-quote each individual word across args or STDIN.

### Newest-file helpers
- **lf** — List the newest `[num]` files (default 1) via `ls -1tr | tail`.
- **llf** — Long listing of the newest `[num]` files.
- **lfp** — Newest `[num]` files in a specified directory, prefixed with the dir path.
- **lfd** — Newest `[num]` directories (by mtime).
- **lfdn** — Same implementation as `lfd`; docstring says "last lexicographically" but the body sorts by mtime.
- **vlf** — `vi` the newest `[num]` files in the current directory.

### Prompt / color helpers
- **colorBG** — Set terminal background via OSC `]11;rgb:` escape. Also bundles many extras: `colorFG`, `colorBGSafe` (hex-validating), `colorBGDec` (decimal inputs), preset `colorBGRed/Green/Blue/...`, `colorReset`, `colorSave`, and scheme helpers `colorSchemeDark/Matrix/Terminal`, plus a `showColorExamples` demo.
- **colorFG** — Set foreground via ANSI `\033[38;5;<n>m` (256-color palette).
- **colorPrompt** — Build a colored `PS1` from history/host/dir/cmdline color indices. Also defines `colorClear`, `colorToken`, `colorTokenNoEnd`, `getColorPrompt` (returns the string without exporting), preset `colorPromptDefault/Blue/Green`, and a `colorsShow` 256-color swatch printer.
- **colorCurrent** — Extract the four current color indices out of `$PS1`.
- **colorChangeFG** — Re-run `colorPrompt` preserving the first three colors and overriding the fourth.
- **colorCMD** — Swap the command-line color (last field of `colorCurrent`) with a new value.
- **colorHistory** — Swap the history-number color (first field).
- **colorHost** — Swap the host color (second field).
- **colorPath** — Swap the dir/path color (third field).
- **colorList** — Print a visual list of all 255 ANSI 256-palette foreground colors as `[color N]` labels.
- **colorRandomCombo** — Standalone script variant (mirrors `color/colorRandomCombo`) that picks random bg/fg from favorites and tees the command to `/c/tmp/latestColorSchema` before applying.

### Interaction
- **promptUser** — Prompt the user with a message and return the reply via `$REPLY`.
