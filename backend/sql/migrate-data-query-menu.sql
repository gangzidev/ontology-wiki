-- 本体驱动数据查询独立入口。执行后重新登录或刷新权限菜单即可看到。
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
VALUES
    (2092, '数据查询', 3001, 4, 'data-query', 'ontology/data-query/index', NULL, 1, 0, 'C', '0', '0', 'ontology:proposals:list', 'monitor', 'admin', NOW())
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name), parent_id=VALUES(parent_id), order_num=VALUES(order_num), path=VALUES(path), component=VALUES(component), menu_type=VALUES(menu_type), visible=VALUES(visible), status=VALUES(status), perms=VALUES(perms), icon=VALUES(icon);

INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, 2092 FROM sys_role_menu WHERE menu_id IN (2005, 2074);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
VALUES
    (2093, '数据查询执行', 2092, 1, '', '', 1, 0, 'F', '1', '0', 'ontology:proposals:query', '', 'admin', NOW())
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name), parent_id=VALUES(parent_id), menu_type=VALUES(menu_type), visible=VALUES(visible), status=VALUES(status), perms=VALUES(perms);

INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, 2093 FROM sys_role_menu WHERE menu_id IN (2005, 2074);
