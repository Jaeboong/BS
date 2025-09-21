import java.net.*;
import java.io.*;
import java.util.*;

public class KTank3 {
    // 통신/환경 설정 (Tank1 호환)
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8747;
    private static String ARGS = "";
    private static Socket socket = null;

    // 맵/상태 데이터 (Tank1 포맷)
    private static String[][] mapData; // 맵 정보
    private static Map<String, String[]> allies = new HashMap<>(); // 아군 정보 (키: 유닛ID, 값: 속성 배열)
    private static Map<String, String[]> enemies = new HashMap<>(); // 적군 정보 (키: 유닛ID, 값: 속성 배열)
    private static String[] codes = new String[0]; // 암호문 정보

    // 좌표/치수
    static int rows = 0, cols = 0;
    static int myX = -1, myY = -1;   // 내 탱크 위치 (심볼: "M")
    static int targetX = -1, targetY = -1; // 적 포탑 위치 (심볼: "X")

    // 내 상태
    static int normalMissiles = 0;
    static int megaMissiles = 0;
    static String myDirection = ""; // U/D/L/R

    // 이동/사격 보조
    static int[] dx = {-1, 1, 0, 0};
    static int[] dy = {0, 0, -1, 1};
    static String[] dirCode = {"U", "D", "L", "R"};

    public static void main(String[] args) throws Exception {
        ARGS = (args != null && args.length > 0) ? args[0] : "";
        String nickname = "통신테스트" + (ARGS != null && ARGS.length() > 0 ? ("_" + ARGS) : "");

        String gameData = init(nickname);
        if (gameData == null || gameData.length() == 0) {
            close();
            return;
        }

        parseData(gameData);

        while (gameData != null && gameData.length() > 0) {
            String output = decideMove();
            gameData = submit(output);
            if (gameData != null && gameData.length() > 0) {
                parseData(gameData);
            }
        }

        close();
    }

    // Tank1 포맷 파싱
    private static void parseData(String gameData) {
        String[] gameDataRows = gameData.split("\n");
        int rowIndex = 0;

        String[] header = gameDataRows[rowIndex].split(" ");
        int mapHeight = header.length >= 1 ? parseIntSafe(header[0]) : 0;
        int mapWidth = header.length >= 2 ? parseIntSafe(header[1]) : 0;
        int numOfAllies = header.length >= 3 ? parseIntSafe(header[2]) : 0;
        int numOfEnemies = header.length >= 4 ? parseIntSafe(header[3]) : 0;
        int numOfCodes = header.length >= 5 ? parseIntSafe(header[4]) : 0;
        rowIndex++;

        rows = mapHeight;
        cols = mapWidth;
        mapData = new String[rows][cols];
        for (int i = 0; i < rows; i++) {
            String[] col = gameDataRows[rowIndex + i].split(" ");
            for (int j = 0; j < col.length; j++) {
                mapData[i][j] = col[j];
            }
        }
        rowIndex += rows;

        allies.clear();
        for (int i = rowIndex; i < rowIndex + numOfAllies; i++) {
            String[] ally = gameDataRows[i].split(" ");
            String allyName = ally.length >= 1 ? ally[0] : "-";
            String[] allyData = new String[ally.length - 1];
            System.arraycopy(ally, 1, allyData, 0, ally.length - 1);
            allies.put(allyName, allyData);
        }
        rowIndex += numOfAllies;

        enemies.clear();
        for (int i = rowIndex; i < rowIndex + numOfEnemies; i++) {
            String[] enemy = gameDataRows[i].split(" ");
            String enemyName = enemy.length >= 1 ? enemy[0] : "-";
            String[] enemyData = new String[enemy.length - 1];
            System.arraycopy(enemy, 1, enemyData, 0, enemy.length - 1);
            enemies.put(enemyName, enemyData);
        }
        rowIndex += numOfEnemies;

        codes = new String[numOfCodes];
        for (int i = 0; i < numOfCodes; i++) {
            codes[i] = gameDataRows[rowIndex + i];
        }

        // 내 위치/상태, 타겟 위치 갱신
        locateSymbols();
        readMyStatus();
    }

    private static void locateSymbols() {
        myX = myY = -1;
        targetX = targetY = -1;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                String cell = mapData[r][c];
                if (cell == null) continue;
                if ("M".equals(cell)) { myX = r; myY = c; }
                else if ("X".equals(cell)) { targetX = r; targetY = c; }
            }
        }
    }

    private static void readMyStatus() {
        String[] me = allies.get("M");
        if (me == null) {
            normalMissiles = 0; megaMissiles = 0; myDirection = "";
            return;
        }
        // me: [hp, dir, normal, mega]
        myDirection = (me.length >= 2) ? me[1] : "";
        normalMissiles = (me.length >= 3) ? parseIntSafe(me[2]) : 0;
        megaMissiles = (me.length >= 4) ? parseIntSafe(me[3]) : 0;
    }

    // 숫자 안전 파싱(try/catch 사용 금지)
    private static int parseIntSafe(String s) {
        if (s == null || s.isEmpty()) return 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (i == 0 && (ch == '+' || ch == '-')) continue;
            if (ch < '0' || ch > '9') return 0;
        }
        return Integer.parseInt(s);
    }

    // KTank1 알고리즘 유지: 1) 사거리 내 사격, 2) 아니면 BFS로 접근
    static String decideMove() {
        if (myX == -1 || myY == -1 || targetX == -1 || targetY == -1) {
            return "S"; // 정보 부족 시 대기
        }

        if (isInFireRange(myX, myY, targetX, targetY)) {
            String dir = getDirectionToTarget(myX, myY, targetX, targetY);
            if (megaMissiles > 0) return dir + " F M";
            if (normalMissiles > 0) return dir + " F";
        }

        int[] next = findShortestPath();
        if (next == null) return "S";
        String moveDir = getDirectionToTarget(myX, myY, next[0], next[1]);
        return moveDir + " A";
    }

    // BFS로 최단경로 한 스텝 반환 (Tank1 이동 규칙: G, S만 보행 가능)
    static int[] findShortestPath() {
        boolean[][] visited = new boolean[rows][cols];
        int[][][] prev = new int[rows][cols][2];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) { prev[r][c][0] = -1; prev[r][c][1] = -1; }
        }

        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.offer(new int[]{myX, myY});
        visited[myX][myY] = true;

        while (!q.isEmpty()) {
            int[] cur = q.poll();
            int x = cur[0], y = cur[1];

            if (x == targetX && y == targetY) {
                List<int[]> path = new ArrayList<>();
                while (x != myX || y != myY) {
                    path.add(new int[]{x, y});
                    int px = prev[x][y][0], py = prev[x][y][1];
                    x = px; y = py;
                }
                Collections.reverse(path);
                return path.isEmpty() ? null : path.get(0);
            }

            for (int i = 0; i < 4; i++) {
                int nx = x + dx[i];
                int ny = y + dy[i];
                if (!inBounds(nx, ny) || visited[nx][ny]) continue;

                // 보행 가능: G 또는 S. 단, 목표지점(X)은 최종 도달 허용(첫 스텝이 X가 되는 경우는 사격 로직이 우선 수행되므로 안전)
                if (canWalk(nx, ny) || (nx == targetX && ny == targetY)) {
                    visited[nx][ny] = true;
                    prev[nx][ny][0] = x;
                    prev[nx][ny][1] = y;
                    q.offer(new int[]{nx, ny});
                }
            }
        }
        return null;
    }

    static boolean inBounds(int x, int y) {
        return x >= 0 && x < rows && y >= 0 && y < cols;
    }

    static boolean canWalk(int x, int y) {
        String cell = mapData[x][y];
        return "G".equals(cell) || "S".equals(cell);
    }

    // 포탄 사거리/시야 판정(직선, 거리<=3, 경로 장애물 없음). KTank1 규칙 유지: G/W만 관통 가능
    static boolean isInFireRange(int fromX, int fromY, int toX, int toY) {
        if (fromX != toX && fromY != toY) return false;
        int distance = Math.abs(fromX - toX) + Math.abs(fromY - toY);
        if (distance > 3) return false;

        if (fromX == toX) {
            int minY = Math.min(fromY, toY);
            int maxY = Math.max(fromY, toY);
            for (int y = minY + 1; y < maxY; y++) {
                if (isObstacle(fromX, y)) return false;
            }
        } else {
            int minX = Math.min(fromX, toX);
            int maxX = Math.max(fromX, toX);
            for (int x = minX + 1; x < maxX; x++) {
                if (isObstacle(x, fromY)) return false;
            }
        }
        return true;
    }

    static boolean isObstacle(int x, int y) {
        String cell = mapData[x][y]; // A,G,R,W,X,T,F,E1,E2 등
        // G, W만 포탄 통과 가능 (KTank1 규칙 유지)
        return !"G".equals(cell) && !"W".equals(cell);
    }

    static String getDirectionToTarget(int fromX, int fromY, int toX, int toY) {
        if (fromX > toX) return "U";
        if (fromX < toX) return "D";
        if (fromY > toY) return "L";
        if (fromY < toY) return "R";
        return myDirection;
    }

    // 통신 (Tank1 스타일, 예외 전파)
    private static String init(String nickname) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(HOST, PORT));
        String initCommand = "INIT " + nickname;
        return submit(initCommand);
    }

    private static String submit(String command) throws IOException {
        OutputStream os = socket.getOutputStream();
        String sendData = ARGS + command + " ";
        os.write(sendData.getBytes("UTF-8"));
        os.flush();
        return receive();
    }

    private static String receive() throws IOException {
        InputStream is = socket.getInputStream();
        byte[] bytes = new byte[1024];
        int length = is.read(bytes);
        if (length <= 0) {
            close();
            return null;
        }
        String gameData = new String(bytes, 0, length, "UTF-8");
        if (gameData.length() > 0 && gameData.charAt(0) >= '1' && gameData.charAt(0) <= '9') {
            return gameData;
        }
        close();
        return null;
    }

    private static void close() throws IOException {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}