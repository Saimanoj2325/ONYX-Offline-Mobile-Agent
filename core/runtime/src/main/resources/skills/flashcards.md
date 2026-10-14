---
id: flashcards
name: Summarise into flashcards
description: Turns a document (or pasted text) into a short summary plus flashcards and a quick quiz.
triggers: [flashcards, flash cards, make cards, quiz me]
tools: [search_docs, read_document, summarize, render_ui]
output: [markdown, flashcards, quiz]
max_steps: 4
---
1. If the user named a document, read it with read_document; otherwise search_docs for the topic they mentioned.
2. Pick the 6-10 most important facts or definitions.
3. Finish with render_ui: a markdown block with a 3-sentence summary, a flashcards block (front = question or term,
   back = short answer), and a quiz block with 3 multiple-choice questions based only on the source.
