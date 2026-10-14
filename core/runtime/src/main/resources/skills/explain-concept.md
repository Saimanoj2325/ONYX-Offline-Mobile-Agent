---
id: explain-concept
name: Explain a concept with steps
description: Explains a concept simply with key facts, a step-by-step breakdown, an example and a self-check quiz.
triggers: [explain, how does, what is, teach me, help me understand]
tools: [search_docs, render_ui]
output: [callout, keyvalue, steps, code, quiz]
max_steps: 3
---
1. If the user's documents cover the concept, search_docs to ground the explanation; otherwise use your own knowledge.
2. Finish with render_ui: a callout with a one-sentence intuition, a keyvalue block of key terms,
   a steps block explaining how it works, a code block only if it is a programming concept, and a 2-question quiz.
Keep language simple and concrete.
