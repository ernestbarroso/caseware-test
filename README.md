# Template-publish fan-out worker: trade-offs

When a template update is published, this worker flags every affected engagement as pending an update. Checking an engagement directly is slow (about 1 minute) and runs on external servers, so most of the trade-offs below are about not overloading them.

**1. Check the lookup table first; open files only as a last resort.**
I decided to use a small lookup table of "which engagement is on which version" and open an engagement only when that table can't answer. I save the result, so the same engagement is never opened twice.
*Gain:* most updates need no slow calls at all. *Cost:* this assumes engagements only ever move forward to newer versions, never back.

**2. A fixed limit on calls to the other team.**
I never run more than an agreed number of checks at the same time.
*Gain:* simple and predictable for users. *Cost:* it doesn't speed up when their servers are quiet or slow down early when they're busy. The limit also applies to each copy of the worker separately, so with several copies the agreed number has to be split between them.

**3. Safe to repeat rather than guaranteed to run once.**
If the worker crashes or receives the same update twice, it simply runs again from where it stopped. Repeating its work changes nothing.
*Gain:* simple and robust. *Cost:* after a crash, a small batch of engagements may be checked twice.

**4. One summary per engagement, not one per update.**
If several updates pile up, users see a single "v3 → v6" change rather than three separate ones. Declining v5 only declines v5; a later v7 that includes it is offered again.
*Gain:* less noise for auditors. *Cost:* each combined summary is built for the user's starting version, so it is less reusable.

**5. One bad engagement doesn't stop the rest.**
Temporary errors are retried after a short wait. Engagements that keep failing are set aside for follow-up instead of blocking the remaining files.
*Gain:* a large firm still gets its updates. *Cost:* set-aside engagements need someone to re-run them.

**6. Old or withdrawn updates stop early.**
If a newer version arrives while an older one is still being processed, the older one stops, because the newer one already includes its changes. Withdrawn versions stop too.
*Gain:* no wasted calls. *Cost:* removing pending updates that were already shown for a withdrawn version is not built yet.
