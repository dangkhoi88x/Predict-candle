-- A deleted account's challenge links keep working (creator_id is ON DELETE SET NULL) but used to
-- keep its display name too, in creator_name. AdminPlayerService now overwrites the name when it
-- deletes an account; this clears the names left behind by deletions made before that.
--
-- A link whose creator was never signed in already holds the anonymous name, so nothing a player
-- still owns is touched.
UPDATE challenges
SET creator_name = 'Một người chơi'
WHERE creator_id IS NULL AND creator_name <> 'Một người chơi';
