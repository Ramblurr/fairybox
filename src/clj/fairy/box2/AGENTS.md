# Fairybox 2 agent guide

`fairy.box2` is a greenfield replacement for `fairy.box`. Keep the two
implementations separate while Box2 is under development.

## Hard boundaries

- Code under `fairy.box2` must not require a `fairy.box` namespace.
    - Unless the human gives specific direction. Just because another ns does it doesnt mean you have permission to.
- Copy and reshape required behavior instead of introducing compatibility
  abstractions between Box1 and Box2.
- Box2 must read and write the existing `db.edn` structure without changing its
  persisted schema. Preserve unknown keys and existing secrets verbatim.
- Never place credentials or the complete database in chart data, events,
  snapshots, logs, or effect payloads.
- Develop with synthetic RFID input on the workstation. Hardware RFID remains
  an adapter added later.

## Statechart invariants

- One application chart session owns orchestration state.
- All external producers submit immutable events through `dispatch!`.
- Only the serialized runtime advances the chart; callbacks never call the
  Statecharts processor directly.
- Guards and state actions are pure. They may inspect the event and chart data,
  but may not dereference the database or perform I/O.
- External work is immutable effect or invocation data and runs off the chart
  processing thread.
- Asynchronous completions return through `dispatch!` and carry request,
  generation, playback-context, invocation, revision, or timer provenance.
- Physical cancellation does not replace stale-event guards.
- Parallel regions model genuinely independent lifecycles. Compound states
  model mutually exclusive modes.
- Put shared transitions at the narrowest meaningful parent. Before targeting
  a descendant from its parent, decide explicitly whether the transition must
  be `:internal` so sibling regions are not reinitialized.
- Give each chart-data subtree one writer. Cross-region coordination occurs
  through events or an explicitly documented handoff.
- One workflow has one refresh owner. Never call Hyperlith refresh concurrently.

## Vocabulary convention

`fairy.box2.model/vocabulary` documents every named state, application event,
and effect.

- Every entry has a short plain-English `:description`.
- Payload-bearing events and effects have a Malli `:payload` schema.
- Schemas describe payload data, not the Statecharts runtime envelope.
- States normally have no payload schema.
- Use literal qualified keywords in the chart. Do not create vars that merely
  alias keyword identifiers.
- Put the identifier kind at the end of its keyword namespace: `.st` for
  states, `.ev` for events, and `.fx` for effects. For example:
  `:audio.st/available`, `:audio.ev/faulted`, and `:media.fx/prepare`.
- The chart is authoritative for hierarchy and transitions; do not duplicate
  that topology in the vocabulary.
- Keep Malli maps open unless a specific boundary requires closed maps.

## Modeling workflow

1. Update the vocabulary when introducing a state, event, or effect.
2. Update the chart topology in `fairy.box2.model`.
3. Reload the namespace in the running REPL.
4. Run `vocabulary-problems`; it must return empty sets and no invalid schemas.
5. Exercise representative transitions through the REPL before writing tests.
6. Add tests only after behavior is polished and observed in the running model.
7. Run `bb qa` at the end.

## Clojure layout

Follow the project Clojure style guide, especially for Statecharts forms:

```clojure
(state {:id      :card-request.st/running
        :initial :card-request.st/resolving}
       (transition {:event  :database.ev/card-resolved
                    :target :card-request.st/preparing})
       (state {:id :card-request.st/resolving})
       (state {:id :card-request.st/preparing}))
```

Keep a constructor beside its attribute map. Align map values and `let`
bindings. Never separate a map key from the opening `{`, `[`, or `(` of its
structured value. Long scalar values, such as vocabulary descriptions, may
continue on the next aligned line. This structural alignment rule applies to
all data maps, including registries, schemas, initial data, runtime data,
vocabulary, and Statecharts attributes. Break between sibling forms, not
between a constructor and its map.

## Current source map

- `fairy.box2.model` — application topology, initial chart data, and vocabulary.

Add new namespaces to this list when their responsibility becomes stable.
