-- Apply once to existing installations. Older citations keep their asset links.
ALTER TABLE wiki_chat_citation ADD COLUMN evidence_json TEXT NULL;
