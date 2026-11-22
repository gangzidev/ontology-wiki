-- Safe migration for existing installations; does not recreate or drop business tables.
START TRANSACTION;

DELETE FROM sys_role_menu WHERE menu_id IN (2006, 2043, 2050, 2051, 2052, 2075);
DELETE FROM sys_menu WHERE menu_id IN (2006, 2043, 2050, 2051, 2052, 2075);

UPDATE ontology_proposal
SET status = 'COMPLETED'
WHERE status = 'APPROVED';

UPDATE ontology_proposal
SET status = 'DRAFT', review_by = NULL, review_time = NULL, review_comment = NULL
WHERE status = 'REJECTED';

COMMIT;
