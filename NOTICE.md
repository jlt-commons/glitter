# Third-party code

## Replicant

The following files under `src/glitter/` are ported from
[Replicant](https://github.com/cjohansen/replicant), commit
`379bb3c1ad4d5d3002c57e67ab647d12f3c2d322` (2026-07-25), by Christian
Johansen. Porting is a mechanical rename (`replicant.*` → `glitter.*`,
including the `:replicant/*` keyword namespace) via
`sed -E 's/\breplicant\b/glitter/g'`, with any further deviation noted in
that file's header comment.

Copyright 2023-2025 Christian Johansen. MIT License:

```
Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

Ported files (path in glitter — original path in replicant):

- `src/glitter/protocols.clj` — `src/replicant/protocols.cljc`
- `src/glitter/hiccup.clj` — `src/replicant/hiccup.cljc`
- `src/glitter/hiccup_headers.clj` — `src/replicant/hiccup_headers.cljc`
- `src/glitter/console_logger.clj` — `src/replicant/console_logger.cljc`
- `src/glitter/errors.clj` — `src/replicant/errors.cljc`
- `src/glitter/assert.clj` — `src/replicant/assert.cljc`
- `src/glitter/vdom.clj` — `src/replicant/vdom.cljc`
- `src/glitter/asserts.clj` — `src/replicant/asserts.cljc`

<!-- appended to by each porting task -->

## glimmer

The following files under `src/glitter/` are forked from
[glimmer](https://github.com/jolt-lang/glimmer) (same author/org as
glitter — no license file, no attribution obligation, listed here for
provenance only):

<!-- appended to by each forking task -->
