package io.mycat.route.util;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.wall.spi.WallVisitorUtils;
import io.mycat.MycatServer;
import io.mycat.cache.LayerCachePool;
import io.mycat.config.ErrorCode;
import io.mycat.config.model.SchemaConfig;
import io.mycat.config.model.TableConfig;
import io.mycat.config.model.rule.RuleConfig;
import io.mycat.route.RouteResultset;
import io.mycat.route.RouteResultsetNode;
import io.mycat.route.function.AbstractPartitionAlgorithm;
import io.mycat.route.parser.druid.DruidParser;
import io.mycat.route.parser.druid.DruidShardingParseInfo;
import io.mycat.route.parser.druid.MycatSchemaStatVisitor;
import io.mycat.route.parser.druid.RouteCalculateUnit;
import io.mycat.server.ServerConnection;
import io.mycat.server.parser.ServerParse;
import io.mycat.server.util.SchemaUtil;
import io.mycat.server.util.SchemaUtil.SchemaInfo;
import io.mycat.sqlengine.mpp.ColumnRoutePair;
import io.mycat.sqlengine.mpp.LoadData;
import io.mycat.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.SQLNonTransientException;
import java.util.*;

/**
 * 从ServerRouterUtil中抽取的一些公用方法，路由解析工具类
 *
 * @author wang.dw
 */
public class RouterUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouterUtil.class);

    public static String removeSchema(String stmt, String schema) {
        return removeSchema(stmt, schema, MycatServer.getInstance().getConfig().getSystem().isLowerCaseTableNames());
    }

    /**
     * 移除执行语句中的数据库名
     *
     * @param stmt        执行语句
     * @param schema      数据库名 ,如果需要，已经小写化过了
     * @param isLowerCase 是否lowercase
     * @return 执行语句
     */
    public static String removeSchema(String stmt, String schema, boolean isLowerCase) {
        final String forCmpStmt = isLowerCase ? stmt.toLowerCase() : stmt;
        final String maySchema1 = schema + ".";
        final String maySchema2 = "`" + schema + "`.";
        int indx1 = forCmpStmt.indexOf(maySchema1, 0);
        int indx2 = forCmpStmt.indexOf(maySchema2, 0);
        if (indx1 < 0 && indx2 < 0) {
            return stmt;
        }
        int strtPos = 0;
        boolean flag;
        int firstE = forCmpStmt.indexOf("'");
        int endE = forCmpStmt.lastIndexOf("'");
        StringBuilder result = new StringBuilder();
        while (indx1 >= 0 || indx2 >= 0) {
            //计算先匹配哪种schema
            if (indx1 < 0 && indx2 >= 0) {
                flag = true;
            } else if (indx1 >= 0 && indx2 < 0) {
                flag = false;
            } else if (indx2 < indx1) {
                flag = true;
            } else {
                flag = false;
            }
            if (flag) {
                result.append(stmt.substring(strtPos, indx2));
                strtPos = indx2 + maySchema2.length();
                if (indx2 > firstE && indx2 < endE && countChar(stmt, indx2) % 2 != 0) {
                    result.append(stmt.substring(indx2, strtPos));
                }
                indx2 = forCmpStmt.indexOf(maySchema2, strtPos);
            } else {
                result.append(stmt.substring(strtPos, indx1));
                strtPos = indx1 + maySchema1.length();
                if (indx1 > firstE && indx1 < endE && countChar(stmt, indx1) % 2 != 0) {
                    result.append(stmt.substring(indx1, strtPos));
                }
                indx1 = forCmpStmt.indexOf(maySchema1, strtPos);
            }
        }
        result.append(stmt.substring(strtPos));
        return result.toString();
    }

    private static int countChar(String sql, int end) {
        int count = 0;
        boolean skipChar = false;
        for (int i = 0; i < end; i++) {
            if (sql.charAt(i) == '\'' && !skipChar) {
                count++;
                skipChar = false;
            } else if (sql.charAt(i) == '\\') {
                skipChar = true;
            } else {
                skipChar = false;
            }
        }
        return count;
    }

    public static RouteResultset routeFromParser(DruidParser druidParser, SchemaConfig schema, RouteResultset rrs, SQLStatement statement,
                                                 String originSql, LayerCachePool cachePool, MycatSchemaStatVisitor visitor, ServerConnection sc) throws SQLException {
        schema = druidParser.parser(schema, rrs, statement, originSql, cachePool, visitor, sc);
        if (rrs.isFinishedExecute()) {
            return null;
        }
        // DruidParser 解析过程中已完成了路由的直接返回
        if (rrs.isFinishedRoute()) {
            return rrs;
        }

        /**
         * 没有from的select语句或其他
         */
        DruidShardingParseInfo ctx = druidParser.getCtx();
        if ((ctx.getTables() == null || ctx.getTables().size() == 0) &&
                (ctx.getTableAliasMap() == null || ctx.getTableAliasMap().isEmpty())) {
            if (schema == null) {
                schema = MycatServer.getInstance().getConfig().getSchemas().get(SchemaUtil.getRandomDb());
            }
            return RouterUtil.routeToSingleNode(rrs, schema.getRandomDataNode());
        }

        /* 多表*/
        if (druidParser.getCtx().getRouteCalculateUnits().size() == 0) {
            RouteCalculateUnit routeCalculateUnit = new RouteCalculateUnit();
            druidParser.getCtx().addRouteCalculateUnit(routeCalculateUnit);
        }

        SortedSet<RouteResultsetNode> nodeSet = new TreeSet<RouteResultsetNode>();
        for (RouteCalculateUnit unit : druidParser.getCtx().getRouteCalculateUnits()) {
            RouteResultset rrsTmp = RouterUtil.tryRouteForTables(schema, druidParser.getCtx(), unit, rrs, isSelect(statement), cachePool);
            if (rrsTmp != null) {
                for (RouteResultsetNode node : rrsTmp.getNodes()) {
                    nodeSet.add(node);
                }
            }
        }

        RouteResultsetNode[] nodes = new RouteResultsetNode[nodeSet.size()];
        int i = 0;
        for (RouteResultsetNode aNodeSet : nodeSet) {
            nodes[i] = aNodeSet;
            i++;
        }
        rrs.setNodes(nodes);


        return rrs;
    }

    /**
     * SELECT 语句
     */
    private static boolean isSelect(SQLStatement statement) {
        if (statement instanceof SQLSelectStatement) {
            return true;
        }
        return false;
    }

    public static void routeToSingleDDLNode(SchemaInfo schemaInfo, RouteResultset rrs) throws SQLException {
        rrs.setSchema(schemaInfo.schema);
        rrs.setTable(schemaInfo.table);
        RouterUtil.routeToSingleNode(rrs, schemaInfo.schemaConfig.getDataNode());
    }

    public static void routeNoNameTableToSingleNode(RouteResultset rrs, SchemaConfig schema) throws SQLNonTransientException {
        if (schema == null) {
            String db = SchemaUtil.getRandomDb();
            if (db == null) {
                String msg = "No schema is configured, make sure your config is right, sql:" + rrs.getStatement();
                throw new SQLNonTransientException(msg);
            }
            schema = MycatServer.getInstance().getConfig().getSchemas().get(db);
        }
        rrs = RouterUtil.routeToSingleNode(rrs, schema.getMetaDataNode());
        rrs.setFinishedRoute(true);
    }

    /**
     * 获取第一个节点作为路由
     *
     * @param rrs      数据路由集合
     * @param dataNode 数据库所在节点
     * @return 数据路由集合
     * @author mycat
     */
    public static RouteResultset routeToSingleNode(RouteResultset rrs, String dataNode) {
        if (dataNode == null) {
            return rrs;
        }
        RouteResultsetNode[] nodes = new RouteResultsetNode[1];
        nodes[0] = new RouteResultsetNode(dataNode, rrs.getSqlType(), rrs.getStatement());
        rrs.setNodes(nodes);
        rrs.setFinishedRoute(true);
        if (rrs.getCanRunInReadDB() != null) {
            nodes[0].setCanRunInReadDB(rrs.getCanRunInReadDB());
        }
        if (rrs.getRunOnSlave() != null) {
            nodes[0].setRunOnSlave(rrs.getRunOnSlave());
        }

        return rrs;
    }


    public static void routeToDDLNode(SchemaInfo schemaInfo, RouteResultset rrs) throws SQLException {
        String stmt = getFixedSql(removeSchema(rrs.getStatement(), schemaInfo.schema));
        List<String> dataNodes;
        Map<String, TableConfig> tables = schemaInfo.schemaConfig.getTables();
        TableConfig tc = tables.get(schemaInfo.table);
        if (tc != null) {
            dataNodes = tc.getDataNodes();
        } else {
            String msg = "Table '" + schemaInfo.schema + "." + schemaInfo.table + "' doesn't exist";
            throw new SQLException(msg, "42S02", ErrorCode.ER_NO_SUCH_TABLE);
        }
        Iterator<String> iterator1 = dataNodes.iterator();
        int nodeSize = dataNodes.size();
        RouteResultsetNode[] nodes = new RouteResultsetNode[nodeSize];

        for (int i = 0; i < nodeSize; i++) {
            String name = iterator1.next();
            nodes[i] = new RouteResultsetNode(name, ServerParse.DDL, stmt);
        }
        rrs.setNodes(nodes);
        rrs.setSchema(schemaInfo.schema);
        rrs.setTable(schemaInfo.table);
        rrs.setFinishedRoute(true);
    }


    /**
     * 处理SQL
     *
     * @param stmt 执行语句
     * @return 处理后SQL
     * @author AStoneGod
     */
    public static String getFixedSql(String stmt) {
        stmt = stmt.replaceAll("\r\n", " "); //对于\r\n的字符 用 空格处理 rainbow
        return stmt = stmt.trim();
    }

    /**
     * 获取table名字
     *
     * @param stmt   执行语句
     * @param repPos 开始位置和位数
     * @return 表名
     * @author AStoneGod
     */
    public static String getTableName(String stmt, int[] repPos) {
        int startPos = repPos[0];
        int secInd = stmt.indexOf(' ', startPos + 1);
        if (secInd < 0) {
            secInd = stmt.length();
        }
        int thiInd = stmt.indexOf('(', secInd + 1);
        if (thiInd < 0) {
            thiInd = stmt.length();
        }
        repPos[1] = secInd;
        String tableName = "";
        if (stmt.toUpperCase().startsWith("DESC") || stmt.toUpperCase().startsWith("DESCRIBE")) {
            tableName = stmt.substring(startPos, thiInd).trim();
        } else {
            tableName = stmt.substring(secInd, thiInd).trim();
        }

        //ALTER TABLE
        if (tableName.contains(" ")) {
            tableName = tableName.substring(0, tableName.indexOf(" "));
        }
        int ind2 = tableName.indexOf('.');
        if (ind2 > 0) {
            tableName = tableName.substring(ind2 + 1);
        }
        return lowerCaseTable(tableName);
    }


    public static String lowerCaseTable(String tableName) {
        if (MycatServer.getInstance().getConfig().getSystem().isLowerCaseTableNames()) {
            return tableName.toLowerCase();
        }
        return tableName;
    }

    /**
     * 获取语句中前关键字位置和占位个数表名位置
     *
     * @param upStmt 执行语句
     * @param start  开始位置
     * @return int[]      关键字位置和占位个数
     * @author mycat
     */
    public static int[] getCreateTablePos(String upStmt, int start) {
        String token1 = "CREATE ";
        String token2 = " TABLE ";
        int createInd = upStmt.indexOf(token1, start);
        int tabInd = upStmt.indexOf(token2, start);
        // 既包含CREATE又包含TABLE，且CREATE关键字在TABLE关键字之前
        if (createInd >= 0 && tabInd > 0 && tabInd > createInd) {
            return new int[]{tabInd, token2.length()};
        } else {
            return new int[]{-1, token2.length()}; // 不满足条件时，只关注第一个返回值为-1，第二个任意
        }
    }


    /**
     * 获取ALTER语句中前关键字位置和占位个数表名位置
     *
     * @param upStmt 执行语句
     * @param start  开始位置
     * @return int[]   关键字位置和占位个数
     * @author aStoneGod
     */
    public static int[] getAlterTablePos(String upStmt, int start) {
        String token1 = "ALTER ";
        String token2 = " TABLE ";
        int createInd = upStmt.indexOf(token1, start);
        int tabInd = upStmt.indexOf(token2, start);
        // 既包含CREATE又包含TABLE，且CREATE关键字在TABLE关键字之前
        if (createInd >= 0 && tabInd > 0 && tabInd > createInd) {
            return new int[]{tabInd, token2.length()};
        } else {
            return new int[]{-1, token2.length()}; // 不满足条件时，只关注第一个返回值为-1，第二个任意
        }
    }

    /**
     * 获取DROP语句中前关键字位置和占位个数表名位置
     *
     * @param upStmt 执行语句
     * @param start  开始位置
     * @return int[]    关键字位置和占位个数
     * @author aStoneGod
     */
    public static int[] getDropTablePos(String upStmt, int start) {
        //增加 if exists判断
        if (upStmt.contains("EXISTS")) {
            String token1 = "IF ";
            String token2 = " EXISTS ";
            int ifInd = upStmt.indexOf(token1, start);
            int tabInd = upStmt.indexOf(token2, start);
            if (ifInd >= 0 && tabInd > 0 && tabInd > ifInd) {
                return new int[]{tabInd, token2.length()};
            } else {
                return new int[]{-1, token2.length()}; // 不满足条件时，只关注第一个返回值为-1，第二个任意
            }
        } else {
            String token1 = "DROP ";
            String token2 = " TABLE ";
            int createInd = upStmt.indexOf(token1, start);
            int tabInd = upStmt.indexOf(token2, start);

            if (createInd >= 0 && tabInd > 0 && tabInd > createInd) {
                return new int[]{tabInd, token2.length()};
            } else {
                return new int[]{-1, token2.length()}; // 不满足条件时，只关注第一个返回值为-1，第二个任意
            }
        }
    }


    /**
     * 获取TRUNCATE语句中前关键字位置和占位个数表名位置
     *
     * @param upStmt 执行语句
     * @param start  开始位置
     * @return int[]    关键字位置和占位个数
     * @author aStoneGod
     */
    public static int[] getTruncateTablePos(String upStmt, int start) {
        String token1 = "TRUNCATE ";
        String token2 = " TABLE ";
        int createInd = upStmt.indexOf(token1, start);
        int tabInd = upStmt.indexOf(token2, start);
        // 既包含CREATE又包含TABLE，且CREATE关键字在TABLE关键字之前
        if (createInd >= 0 && tabInd > 0 && tabInd > createInd) {
            return new int[]{tabInd, token2.length()};
        } else {
            return new int[]{-1, token2.length()}; // 不满足条件时，只关注第一个返回值为-1，第二个任意
        }
    }


    public static RouteResultset routeToMultiNode(boolean cache, RouteResultset rrs, Collection<String> dataNodes) {
        RouteResultsetNode[] nodes = new RouteResultsetNode[dataNodes.size()];
        int i = 0;
        RouteResultsetNode node;
        for (String dataNode : dataNodes) {
            node = new RouteResultsetNode(dataNode, rrs.getSqlType(), rrs.getStatement());
            if (rrs.getCanRunInReadDB() != null) {
                node.setCanRunInReadDB(rrs.getCanRunInReadDB());
            }
            if (rrs.getRunOnSlave() != null) {
                nodes[0].setRunOnSlave(rrs.getRunOnSlave());
            }
            nodes[i++] = node;
        }
        rrs.setCacheAble(cache);
        rrs.setNodes(nodes);
        return rrs;
    }

    public static RouteResultset routeToMultiNode(boolean cache, RouteResultset rrs, Collection<String> dataNodes, boolean isGlobalTable) {
        rrs = routeToMultiNode(cache, rrs, dataNodes);
        rrs.setGlobalTable(isGlobalTable);
        return rrs;
    }

    public static void routeToRandomNode(RouteResultset rrs,
                                         SchemaConfig schema, String tableName) throws SQLException {
        String dataNode = getRandomDataNode(schema, tableName);
        routeToSingleNode(rrs, dataNode);
    }

    /**
     * 根据标名随机获取一个节点
     *
     * @param schema 数据库名
     * @param table  表名
     * @return 数据节点
     * @author mycat
     */
    private static String getRandomDataNode(SchemaConfig schema,
                                            String table) throws SQLException {
        String dataNode = null;
        Map<String, TableConfig> tables = schema.getTables();
        TableConfig tc;
        if (tables != null && (tc = tables.get(table)) != null) {
            dataNode = tc.getRandomDataNode();
        } else {
            String msg = "Table '" + schema.getName() + "." + table + "' doesn't exist";
            throw new SQLException(msg, "42S02", ErrorCode.ER_NO_SUCH_TABLE);
        }
        return dataNode;
    }

    public static Set<String> ruleByJoinValueCalculate(RouteResultset rrs, TableConfig tc,
                                                       Set<ColumnRoutePair> colRoutePairSet) throws SQLNonTransientException {
        Set<String> retNodeSet = new LinkedHashSet<String>();
        if (tc.getDirectRouteTC() != null) {
            Set<String> nodeSet = ruleCalculate(tc.getDirectRouteTC(), colRoutePairSet);
            if (nodeSet.isEmpty()) {
                throw new SQLNonTransientException("parent key can't find  valid datanode ,expect 1 but found: " + nodeSet.size());
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("found partion node (using parent partion rule directly) for child table to insert  " + nodeSet + " sql :" + rrs.getStatement());
            }
            retNodeSet.addAll(nodeSet);
            return retNodeSet;
        } else {
            retNodeSet.addAll(tc.getParentTC().getDataNodes());
        }
        return retNodeSet;
    }

    public static Set<String> ruleCalculate(TableConfig tc, Set<ColumnRoutePair> colRoutePairSet) {
        Set<String> routeNodeSet = new LinkedHashSet<String>();
        String col = tc.getRule().getColumn();
        RuleConfig rule = tc.getRule();
        AbstractPartitionAlgorithm algorithm = rule.getRuleAlgorithm();
        for (ColumnRoutePair colPair : colRoutePairSet) {
            if (colPair.colValue != null) {
                Integer nodeIndx = algorithm.calculate(colPair.colValue);
                if (nodeIndx == null) {
                    throw new IllegalArgumentException("can't find datanode for sharding column:" + col + " val:" + colPair.colValue);
                } else {
                    String dataNode = tc.getDataNodes().get(nodeIndx);
                    routeNodeSet.add(dataNode);
                    colPair.setNodeId(nodeIndx);
                }
            } else if (colPair.rangeValue != null) {
                Integer[] nodeRange = algorithm.calculateRange(String.valueOf(colPair.rangeValue.beginValue), String.valueOf(colPair.rangeValue.endValue));
                if (nodeRange != null) {
                    /**
                     * 不能确认 colPair的 nodeid是否会有其它影响
                     */
                    if (nodeRange.length == 0) {
                        routeNodeSet.addAll(tc.getDataNodes());
                    } else {
                        ArrayList<String> dataNodes = tc.getDataNodes();
                        String dataNode = null;
                        for (Integer nodeId : nodeRange) {
                            dataNode = dataNodes.get(nodeId);
                            routeNodeSet.add(dataNode);
                        }
                    }
                }
            }

        }
        return routeNodeSet;
    }

    /**
     * 多表路由
     */
    public static RouteResultset tryRouteForTables(SchemaConfig schema, DruidShardingParseInfo ctx,
                                                   RouteCalculateUnit routeUnit, RouteResultset rrs, boolean isSelect, LayerCachePool cachePool)
            throws SQLException {

        List<String> tables = ctx.getTables();

        // no sharding table
        if (isNoSharding(schema, tables.get(0))) {
            return routeToSingleNode(rrs, schema.getDataNode());
        }

        //只有一个表的
        if (tables.size() == 1) {
            return RouterUtil.tryRouteForOneTable(schema, routeUnit, tables.get(0), rrs, isSelect, cachePool);
        }

        /**
         * 多表 一定是ER关系的以及global* normal表, global* er表的join
         */
        //每个表对应的路由映射 <table,datanodes>
        Map<String, Set<String>> tablesRouteMap = new HashMap<String, Set<String>>();

        //分库解析信息不为空
        Map<String, Map<String, Set<ColumnRoutePair>>> tablesAndConditions = routeUnit.getTablesAndConditions();
        if (tablesAndConditions != null && tablesAndConditions.size() > 0) {
            //为分库表找路由
            RouterUtil.findRouteWithcConditionsForTables(schema, rrs, tablesAndConditions, tablesRouteMap, cachePool, isSelect, false);
            if (rrs.isFinishedRoute()) {
                return rrs;
            }
        }

        //为单库表找路由,全局表不改变结果集，全局表*任意表 无交集的已经退化为普通表join了
        for (String tableName : tables) {
            TableConfig tableConfig = schema.getTables().get(tableName);
            if (tableConfig != null && !tableConfig.isGlobalTable() && tablesRouteMap.get(tableName) == null) { // 余下的表都是单库表
                tablesRouteMap.put(tableName, new HashSet<String>());
                tablesRouteMap.get(tableName).addAll(tableConfig.getDataNodes());
            }
        }

        Set<String> retNodesSet = new HashSet<String>();
        boolean isFirstAdd = true;
        for (Map.Entry<String, Set<String>> entry : tablesRouteMap.entrySet()) {
            if (entry.getValue() == null || entry.getValue().size() == 0) {
                throw new SQLNonTransientException("parent key can't find any valid datanode ");
            } else {
                if (isFirstAdd) {
                    retNodesSet.addAll(entry.getValue());
                    isFirstAdd = false;
                } else {
                    retNodesSet.retainAll(entry.getValue());
                    if (retNodesSet.size() == 0) { //两个表的路由无交集
                        String errMsg = "invalid route in sql, multi tables found but datanode has no intersection " +
                                " sql:" + rrs.getStatement();
                        LOGGER.warn(errMsg);
                        throw new SQLNonTransientException(errMsg);
                    }
                }
            }
        }
        //retNodesSet.size() >0
        routeToMultiNode(isSelect, rrs, retNodesSet);
        return rrs;

    }

    /**
     * 单表路由
     */
    public static RouteResultset tryRouteForOneTable(SchemaConfig schema,
                                                     RouteCalculateUnit routeUnit, String tableName, RouteResultset rrs, boolean isSelect,
                                                     LayerCachePool cachePool) throws SQLException {
        TableConfig tc = schema.getTables().get(tableName);
        if (tc == null) {
            String msg = "Table '" + schema.getName() + "." + tableName + "' doesn't exist";
            throw new SQLException(msg, "42S02", ErrorCode.ER_NO_SUCH_TABLE);
        }


        if (tc.isGlobalTable()) { //全局表
            if (isSelect) {
                // global select ,not cache route result
                rrs.setCacheAble(false);
                return routeToSingleNode(rrs, tc.getRandomDataNode());
            } else { //insert into 全局表的记录
                return routeToMultiNode(false, rrs, tc.getDataNodes(), true);
            }
        } else { //单表或者分库表
            if (!checkRuleRequired(schema, routeUnit, tc)) {
                throw new IllegalArgumentException("route rule for table " +
                        tc.getName() + " is required: " + rrs.getStatement());

            }
            if ((tc.getPartitionColumn() == null && tc.getParentTC() == null) ||
                    (tc.getParentTC() != null && tc.getDirectRouteTC() == null)) {
                // 单表路由  或者 复杂ER表中的某个子表
                return routeToMultiNode(rrs.isCacheAble(), rrs, tc.getDataNodes());
            } else {
                //每个表对应的路由映射
                Map<String, Set<String>> tablesRouteMap = new HashMap<String, Set<String>>();
                if (routeUnit.getTablesAndConditions() != null && routeUnit.getTablesAndConditions().size() > 0) {
                    RouterUtil.findRouteWithcConditionsForTables(schema, rrs, routeUnit.getTablesAndConditions(), tablesRouteMap, cachePool, isSelect, true);
                    if (rrs.isFinishedRoute()) {
                        return rrs;
                    }
                }
                if (tablesRouteMap.get(tableName) == null) {
                    return routeToMultiNode(rrs.isCacheAble(), rrs, tc.getDataNodes());
                } else {
                    return routeToMultiNode(rrs.isCacheAble(), rrs, tablesRouteMap.get(tableName));
                }
            }
        }
    }


    private static boolean tryRouteWithPrimaryCache(
            RouteResultset rrs, Map<String, Set<String>> tablesRouteMap,
            LayerCachePool cachePool, Map<String, Set<ColumnRoutePair>> columnsMap,
            SchemaConfig schema, String tableName, String primaryKey, boolean isSelect) {
        if (cachePool == null || primaryKey == null || columnsMap.get(primaryKey) == null) {
            return false;
        }
        if (LOGGER.isDebugEnabled() && rrs.getStatement().startsWith(LoadData.LOAD_DATA_HINT) || rrs.isLoadData()) {
            //由于load data一次会计算很多路由数据，如果输出此日志会极大降低load data的性能
            return false;
        }
        //try by primary key if found in cache
        Set<ColumnRoutePair> primaryKeyPairs = columnsMap.get(primaryKey);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("try to find cache by primary key ");
        }

        String tableKey = StringUtil.getFullName(schema.getName(), tableName, '_');
        boolean allFound = true;
        for (ColumnRoutePair pair : primaryKeyPairs) { // 可能id in(1,2,3)多主键
            String cacheKey = pair.colValue;
            String dataNode = (String) cachePool.get(tableKey, cacheKey);
            if (dataNode == null) {
                allFound = false;
                break;
            } else {
                if (tablesRouteMap.get(tableName) == null) {
                    tablesRouteMap.put(tableName, new HashSet<String>());
                }
                tablesRouteMap.get(tableName).add(dataNode);
            }
        }
        if (!allFound && isSelect) {
            // need cache primary key ->dataNode relation
            rrs.setPrimaryKey(tableKey + '.' + primaryKey);
        }
        return allFound;
    }

    /**
     * 处理分库表路由
     */
    public static void findRouteWithcConditionsForTables(SchemaConfig schema, RouteResultset rrs,
                                                         Map<String, Map<String, Set<ColumnRoutePair>>> tablesAndConditions,
                                                         Map<String, Set<String>> tablesRouteMap, LayerCachePool cachePool,
                                                         boolean isSelect, boolean isSingleTable) throws SQLNonTransientException {

        //为分库表找路由
        for (Map.Entry<String, Map<String, Set<ColumnRoutePair>>> entry : tablesAndConditions.entrySet()) {
            String tableName = entry.getKey();
            if (MycatServer.getInstance().getConfig().getSystem().isLowerCaseTableNames()) {
                tableName = tableName.toLowerCase();
            }
            if (tableName.startsWith(schema.getName() + ".")) {
                tableName = tableName.substring(schema.getName().length() + 1);
            }
            TableConfig tableConfig = schema.getTables().get(tableName);
            if (tableConfig == null) {
                if (isSingleTable) {
                    String msg = "can't find table [" + tableName + "[ define in schema " + ":" + schema.getName();
                    LOGGER.warn(msg);
                    throw new SQLNonTransientException(msg);
                } else {
                    //cross to other schema
                    continue;
                }
            }
            if (tableConfig.isGlobalTable() || schema.getTables().get(tableName).getDataNodes().size() == 1) {
                //global table or single node shard-ing table will router later
                continue;
            } else { //非全局表：分库表、childTable、其他
                Map<String, Set<ColumnRoutePair>> columnsMap = entry.getValue();
                if (tryRouteWithPrimaryCache(rrs, tablesRouteMap, cachePool, columnsMap, schema, tableName, tableConfig.getPrimaryKey(), isSelect)) {
                    continue;
                }

                String joinKey = tableConfig.getJoinKey();
                String partionCol = tableConfig.getPartitionColumn();
                boolean isFoundPartitionValue = partionCol != null && columnsMap.get(partionCol) != null;

                // where filter contains partition column
                if (isFoundPartitionValue) {
                    Set<ColumnRoutePair> partitionValue = columnsMap.get(partionCol);
                    if (partitionValue.size() == 0) {
                        if (tablesRouteMap.get(tableName) == null) {
                            tablesRouteMap.put(tableName, new HashSet<String>());
                        }
                        tablesRouteMap.get(tableName).addAll(tableConfig.getDataNodes());
                    } else {
                        for (ColumnRoutePair pair : partitionValue) {
                            AbstractPartitionAlgorithm algorithm = tableConfig.getRule().getRuleAlgorithm();
                            if (pair.colValue != null) {
                                Integer nodeIndex = algorithm.calculate(pair.colValue);
                                if (nodeIndex == null) {
                                    String msg = "can't find any valid datanode :" + tableConfig.getName() +
                                            " -> " + tableConfig.getPartitionColumn() + " -> " + pair.colValue;
                                    LOGGER.warn(msg);
                                    throw new SQLNonTransientException(msg);
                                }

                                ArrayList<String> dataNodes = tableConfig.getDataNodes();
                                String node;
                                if (nodeIndex >= 0 && nodeIndex < dataNodes.size()) {
                                    node = dataNodes.get(nodeIndex);

                                } else {
                                    node = null;
                                    String msg = "Can't find a valid data node for specified node index :" +
                                            tableConfig.getName() + " -> " + tableConfig.getPartitionColumn() +
                                            " -> " + pair.colValue + " -> " + "Index : " + nodeIndex;
                                    LOGGER.warn(msg);
                                    throw new SQLNonTransientException(msg);
                                }
                                if (node != null) {
                                    if (tablesRouteMap.get(tableName) == null) {
                                        tablesRouteMap.put(tableName, new HashSet<String>());
                                    }
                                    tablesRouteMap.get(tableName).add(node);
                                }
                            }
                            if (pair.rangeValue != null) {
                                Integer[] nodeIndexs = algorithm
                                        .calculateRange(pair.rangeValue.beginValue.toString(), pair.rangeValue.endValue.toString());
                                ArrayList<String> dataNodes = tableConfig.getDataNodes();
                                String node;
                                for (Integer idx : nodeIndexs) {
                                    if (idx >= 0 && idx < dataNodes.size()) {
                                        node = dataNodes.get(idx);
                                    } else {
                                        String msg = "Can't find valid data node(s) for some of specified node indexes :" +
                                                tableConfig.getName() + " -> " + tableConfig.getPartitionColumn();
                                        LOGGER.warn(msg);
                                        throw new SQLNonTransientException(msg);
                                    }
                                    if (node != null) {
                                        if (tablesRouteMap.get(tableName) == null) {
                                            tablesRouteMap.put(tableName, new HashSet<String>());
                                        }
                                        tablesRouteMap.get(tableName).add(node);

                                    }
                                }
                            }
                        }
                    }
                } else if (joinKey != null && columnsMap.get(joinKey) != null && columnsMap.get(joinKey).size() != 0) {
                    //childTable  (如果是select 语句的父子表join)之前要找到root table,将childTable移除,只留下root table
                    Set<ColumnRoutePair> joinKeyValue = columnsMap.get(joinKey);

                    Set<String> dataNodeSet = ruleByJoinValueCalculate(rrs, tableConfig, joinKeyValue);

                    if (dataNodeSet.isEmpty()) {
                        throw new SQLNonTransientException(
                                "parent key can't find any valid datanode ");
                    }
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("found partion nodes (using parent partion rule directly) for child table to update  " +
                                Arrays.toString(dataNodeSet.toArray()) + " sql :" + rrs.getStatement());
                    }
                    if (dataNodeSet.size() > 1) {
                        routeToMultiNode(rrs.isCacheAble(), rrs, dataNodeSet);
                        rrs.setFinishedRoute(true);
                        return;
                    } else {
                        rrs.setCacheAble(true);
                        routeToSingleNode(rrs, dataNodeSet.iterator().next());
                        return;
                    }

                } else {
                    //没找到拆分字段，该表的所有节点都路由
                    if (tablesRouteMap.get(tableName) == null) {
                        tablesRouteMap.put(tableName, new HashSet<String>());
                    }
                    tablesRouteMap.get(tableName).addAll(tableConfig.getDataNodes());
                }
            }
        }
    }

    /**
     * @param schema
     * @param tc
     * @return true表示校验通过，false表示检验不通过
     */
    public static boolean checkRuleRequired(SchemaConfig schema, RouteCalculateUnit routeUnit, TableConfig tc) {
        if (!tc.isRuleRequired()) {
            return true;
        }
        boolean hasRequiredValue = false;
        String tableName = tc.getName();
        if (routeUnit.getTablesAndConditions().get(tableName) == null || routeUnit.getTablesAndConditions().get(tableName).size() == 0) {
            hasRequiredValue = false;
        } else {
            for (Map.Entry<String, Set<ColumnRoutePair>> condition : routeUnit.getTablesAndConditions().get(tableName).entrySet()) {

                String colName = RouterUtil.getFixedSql(RouterUtil.removeSchema(condition.getKey(), schema.getName()));
                //条件字段是拆分字段
                if (colName.equals(tc.getPartitionColumn())) {
                    hasRequiredValue = true;
                    break;
                }
            }
        }
        return hasRequiredValue;
    }


    /**
     * 增加判断支持未配置分片的表走默认的dataNode
     *
     * @param schemaConfig
     * @param tableName
     * @return
     */
    public static boolean isNoSharding(SchemaConfig schemaConfig, String tableName) {
        if (schemaConfig == null) {
            return false;
        }
        if (schemaConfig.isNoSharding()) {
            return true;
        }
        if (schemaConfig.getDataNode() != null && !schemaConfig.getTables().containsKey(tableName)) {
            return true;
        }
        return false;
    }

    /**
     * 判断条件是否永真
     *
     * @param expr
     * @return
     */
    public static boolean isConditionAlwaysTrue(SQLExpr expr) {
        Object o = WallVisitorUtils.getValue(expr);
        if (Boolean.TRUE.equals(o)) {
            return true;
        }
        return false;
    }

    /**
     * 判断条件是否永假的
     *
     * @param expr
     * @return
     */
    public static boolean isConditionAlwaysFalse(SQLExpr expr) {
        Object o = WallVisitorUtils.getValue(expr);
        if (Boolean.FALSE.equals(o)) {
            return true;
        }
        return false;
    }
}
