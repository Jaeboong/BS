import java.net.*;
import java.io.*;
import java.util.*;

public class MTank1 {
    /////////////////////////////////
    // 메인 프로그램 통신 변수 정의
    /////////////////////////////////
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8747;
    private static String ARGS = "";
    private static Socket socket = null;

    ///////////////////////////////
    // 입력 데이터 변수 정의
    ///////////////////////////////
    private static String[][] mapData; // 맵 정보. 예) mapData[0][1] - [0, 1]의 지형/지물
    private static Map<String, String[]> myAllies = new HashMap<>(); // 아군 정보. 예) myAllies['M'] - 플레이어 본인의 정보
    private static Map<String, String[]> enemies = new HashMap<>(); // 적군 정보. 예) enemies['X'] - 적 포탑의 정보
    private static String[] codes; // 주어진 암호문. 예) codes[0] - 첫 번째 암호문
    // 포탑 방어 파라미터
    private static final int TEAM_DEF_RADIUS = 6; // 포탑 수비 반경
    private static final int ENEMY_APPROACH_TO_H = 4; // 포탑 접근 판정 거리
    // 선제 수비 모드 파라미터 및 상태
    private static final int PROACTIVE_DEF_DIST = 5; // 선제 수비 거리 임계
    private static int prevMinDistToH = Integer.MAX_VALUE; // 직전 H-적 최소 거리
    private static int approachTrendCount = 0; // 연속 감소 턴 수
    private static boolean defenseProactiveFlag = false; // 선제 수비 모드 플래그
    // 교착/교전 추적 상태
    private static int lastR = -1, lastC = -1; // 직전 좌표
    private static int lastMoveDir = -1; // 0:R,1:D,2:L,3:U
    private static boolean lastWasMove = false; // 직전 명령이 이동인지
    private static boolean recentStuck = false; // 직전 이동 실패
    private static int lastNearestEnemyDist = -1; // 직전 나-최근접 적 거리
    private static boolean enemyRetreatingFlag = false; // 적이 멀어지는 추세
    
    public static void main(String[] args) {
        ARGS = args.length > 0 ? args[0] : "";
        
        ///////////////////////////////
        // 닉네임 설정 및 최초 연결
        ///////////////////////////////
        String NICKNAME = "개척자" + (ARGS != null && ARGS.length() > 0 ? ("_"+ARGS) : "");
        String gameData = init(NICKNAME);
        if (gameData == null || gameData.length() == 0) {
            close();
            return;
        }
        
        ///////////////////////////////
        // 알고리즘 메인 부분 구현 시작
        ///////////////////////////////
        
        int[][] DIRS = {{0,1}, {1,0}, {0,-1}, {-1,0}};
        String[] MOVE_CMDS = {"R A", "D A", "L A", "U A"};
        String[] FIRE_CMDS = {"R F", "D F", "L F", "U F"};
        String[] FIRE_M_CMDS = {"R F M", "D F M", "L F M", "U F M"};
        String START_SYMBOL = "M";
        String TARGET_SYMBOL = "X";
        int MEGA_TARGET_PER_TANK = 2; // 최소 보급 수량: 탱크당 2발 확보
        int TEAM_MEGA_MIN_RESERVE = 3; // 팀 최소 보급 총량
        int EARLY_SUPPLY_TURNS = 8; // 초반 보급 우선 턴수
        int turn = 0; // 진행 턴 카운터

        // 최초 데이터 파싱
        parseData(gameData);
        
        // 출발지점, 목표지점의 위치 확인
        int[][] positions = findPositions(mapData, START_SYMBOL, TARGET_SYMBOL);
        int[] start = positions[0];
        int[] target = positions[1];
        if (start == null || target == null) {
            System.out.println("[ERROR] Start or target not found in map");
            close();
            return;
        }

        // 반복문: 메인 프로그램 <-> 클라이언트(이 코드) 간 순차로 데이터 송수신(동기 처리)
        while (gameData != null && gameData.length() > 0) {
        	
            // 파싱한 데이터를 화면에 출력하여 확인
            printData(gameData);

            // 내 위치/방향/탄약 파악
            int[] myPos = findPositions(mapData, START_SYMBOL, TARGET_SYMBOL)[0];
            String[] me = myAllies.get("M");
            int myMega = me != null && me.length >= 4 ? parseIntSafe(me[3]) : 0;

            // 선제 수비 모드 추세 갱신
            int curMinDistToH = getMinDistEnemyToH(mapData);
            if (curMinDistToH < prevMinDistToH) approachTrendCount++; else approachTrendCount = 0;
            prevMinDistToH = curMinDistToH;
            defenseProactiveFlag = (curMinDistToH > -1 && curMinDistToH <= PROACTIVE_DEF_DIST) || (approachTrendCount >= 2);
            // 적 후퇴 추세 갱신(나 기준)
            int curNearestEnemyDist = distanceToNearestEnemy(mapData, myPos);
            enemyRetreatingFlag = (lastNearestEnemyDist != -1 && curNearestEnemyDist > lastNearestEnemyDist);

            // 1) 사격 우선: 사거리 3 직선, R/T 차폐, W 관통
            LoSResult los = findFireLineOfSight(mapData, myPos, DIRS);
            String output;
            if (los.canFire) {
            	// 대상 체력 기준: 30 초과 → 메가, 30 이하 → 일반
            	int targetHp = getTargetHp(los.targetSymbol);
            	boolean useMega = myMega > 0 && targetHp > 30;
            	output = useMega ? FIRE_M_CMDS[los.dirIndex] : FIRE_CMDS[los.dirIndex];
            } else if (isAdjacentToSupply(mapData, myPos, DIRS)) {
            	// 2) 보급: 인접 시 코드 있으면 즉시 해독, 없으면 F 인접 유지(오빗)
                boolean earlyPhase = turn < EARLY_SUPPLY_TURNS;
                boolean needMega = (myMega < MEGA_TARGET_PER_TANK) && (estimateTeamMega() < TEAM_MEGA_MIN_RESERVE || earlyPhase) && (myMega < 10);
                boolean codesAvailable = (codes != null && codes.length > 0);
                if (codesAvailable && needMega) {
                    String cipher = codes[0].trim();
                    String plain = decodeCaesarShift(cipher, 9);
                    output = "G " + plain;
                } else {
                    output = chooseSupplyOrbitMove(mapData, myPos, DIRS, MOVE_CMDS);
                }
            } else {
            	// 3) 이동: 보급 필요시 F로 (극초반 강제), 아니면 방어선 패트롤(정지 금지)
            	boolean[][] threat = buildThreatMap(mapData, DIRS);
            	boolean approaching = isEnemyApproaching(mapData, myPos);
                // 최근 교착 여부 갱신
                if (lastR != -1 && lastC != -1 && lastWasMove && myPos[0]==lastR && myPos[1]==lastC) {
                	recentStuck = true;
                } else {
                	recentStuck = false;
                }
                boolean earlyPhase = turn < EARLY_SUPPLY_TURNS;
                boolean needMega = (myMega < MEGA_TARGET_PER_TANK) && (estimateTeamMega() < TEAM_MEGA_MIN_RESERVE || earlyPhase) && (myMega < 10);
                boolean codesAvailable = (codes != null && codes.length > 0);
                boolean myTurnForSupply = isMySupplyTurn(turn);
                int distToF = distanceToNearestSupplyAdjacency(mapData, myPos, DIRS);
                int distToEnemy = distanceToNearestEnemy(mapData, myPos);
                boolean supplyPreferred = needMega && codesAvailable && (distToF >= 0) && (distToEnemy < 0 || distToF < distToEnemy);
                // 근접 링 제어: F까지 2 이내면 초반 제외하고 내 차례 아닐 땐 진입 억제
                if (!earlyPhase && distToF >= 0 && distToF <= 2 && !myTurnForSupply) supplyPreferred = false;
            	if (earlyPhase && needMega) {
            		Queue<String> pathToF = aStarToSupplyAdjacency(mapData, myPos, DIRS, MOVE_CMDS, threat, turn);
            		output = (pathToF != null && !pathToF.isEmpty()) ? pathToF.poll() : chooseSupplyOrbitMove(mapData, myPos, DIRS, MOVE_CMDS);
            	} else if (supplyPreferred || (needMega && codesAvailable && myTurnForSupply && !approaching && !threat[myPos[0]][myPos[1]])) {
            		Queue<String> pathToF = aStarToSupplyAdjacency(mapData, myPos, DIRS, MOVE_CMDS, threat, turn);
            		output = (pathToF != null && !pathToF.isEmpty()) ? pathToF.poll() : chooseSupplyOrbitMove(mapData, myPos, DIRS, MOVE_CMDS);
            	} else {
            		output = choosePatrolMove(mapData, myPos, DIRS, MOVE_CMDS, threat);
            	}
            }

            // 이동 기록 갱신
            boolean isMoveCmd = output.endsWith("A") && !output.contains(" F");
            lastWasMove = isMoveCmd;
            if (isMoveCmd) {
            	char ch = output.charAt(0);
            	lastMoveDir = (ch=='R'?0: ch=='D'?1: ch=='L'?2: 3);
            } else {
            	lastMoveDir = -1;
            }
            lastR = myPos[0]; lastC = myPos[1];
            lastNearestEnemyDist = curNearestEnemyDist;
            // 메인 프로그램에서 명령을 처리할 수 있도록 명령어를 submit()의 인자로 전달
            gameData = submit(output);

            // submit()의 리턴으로 받은 갱신된 데이터를 다시 파싱
            if (gameData != null && gameData.length() > 0) {
                parseData(gameData);
            }
            turn++;
        }

        ///////////////////////////////
        // 알고리즘 메인 부분 구현 끝
        ///////////////////////////////
        
        // 반복문을 빠져나왔을 때 메인 프로그램과의 연결을 완전히 해제하기 위해 close() 호출
        close();
    }

    ////////////////////////////////////
    // 알고리즘 함수/메서드 부분 구현 시작
    ////////////////////////////////////
    
    // 특정 기호의 위치 찾기
	private static int[][] findPositions(String[][] grid, String startMark, String targetMark) {
	    int rows = grid.length;
	    int cols = grid[0].length;
	    int[] start = null;
	    int[] target = null;
	
	    for (int row = 0; row < rows; row++) {
	        for (int col = 0; col < cols; col++) {
	            if (grid[row][col].equals(startMark)) {
	                start = new int[]{row, col};
	            } else if (grid[row][col].equals(targetMark)) {
	                target = new int[]{row, col};
	            }
	        }
	    }
	
	    return new int[][]{start, target};
	}

    // (미사용) 기존 X 타겟 BFS는 제거되었습니다.

    // 숫자 안전 파싱(try/catch 미사용)
    private static int parseIntSafe(String s) {
    	if (s == null || s.isEmpty()) return 0;
    	for (int i = 0; i < s.length(); i++) {
    		char ch = s.charAt(i);
    		if (i == 0 && (ch == '+' || ch == '-')) continue;
    		if (ch < '0' || ch > '9') return 0;
    	}
    	return Integer.parseInt(s);
    }

    // 라인 오브 사이트 결과
    private static class LoSResult {
    	boolean canFire;
    	int dirIndex;
    	String targetSymbol;
    	LoSResult(boolean canFire, int dirIndex, String targetSymbol) {
    		this.canFire = canFire;
    		this.dirIndex = dirIndex;
    		this.targetSymbol = targetSymbol;
    	}
    }

    // 사거리 3, R/T/F 및 아군(M/H/M1/M2) 차폐, W 관통, 첫 타격 대상(E*, X) 탐색
    private static LoSResult findFireLineOfSight(String[][] grid, int[] start, int[][] dirs) {
        int rows = grid.length;
        int cols = grid[0].length;
    	for (int d = 0; d < dirs.length; d++) {
    		int r = start[0];
    		int c = start[1];
    		for (int k = 1; k <= 3; k++) {
    			r += dirs[d][0];
    			c += dirs[d][1];
    			if (r < 0 || r >= rows || c < 0 || c >= cols) break;
    			String cell = grid[r][c];
    			if (cell == null) break;
                // 차폐물: R/T/F
                if (cell.equals("R") || cell.equals("T") || cell.equals("F")) break;
                // 아군 유닛 차폐: M/H/M1/M2 등
                if (cell.equals("M") || cell.equals("H") || cell.startsWith("M")) break;
    			if (cell.equals("X") || cell.startsWith("E")) {
    				return new LoSResult(true, d, cell);
    			}
    			// W는 관통, G/S 등은 계속 진행
    		}
    	}
    	return new LoSResult(false, -1, null);
    }

    // 대상 HP 조회(가능 시). 없으면 0 반환
    private static int getTargetHp(String symbol) {
    	if (symbol == null) return 0;
    	String[] info = enemies.get(symbol);
    	if (info == null && symbol.equals("X")) info = enemies.get("X");
    	if (info == null) return 0;
    	return parseIntSafe(info[0]);
    }

    // 위협 맵 생성: 적(E*, X) 기준 사거리 3 직선 라인(차폐 R/T/F 및 유닛) 내 타일을 위협으로 표시
    private static boolean[][] buildThreatMap(String[][] grid, int[][] dirs) {
    	int rows = grid.length, cols = grid[0].length;
    	boolean[][] threat = new boolean[rows][cols];
    	for (int r = 0; r < rows; r++) {
    		for (int c = 0; c < cols; c++) {
    			String cell = grid[r][c];
    			if (cell == null) continue;
    			if (cell.equals("X") || cell.startsWith("E")) {
    				for (int d = 0; d < dirs.length; d++) {
    					int nr = r, nc = c;
    					for (int k = 1; k <= 3; k++) {
    						nr += dirs[d][0]; nc += dirs[d][1];
    						if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) break;
    						String cc = grid[nr][nc]; if (cc == null) break;
    						if (cc.equals("R") || cc.equals("T") || cc.equals("F")) break;
    						if (cc.equals("M") || cc.equals("H") || cc.startsWith("M") || cc.startsWith("E") || cc.equals("X")) break;
    						threat[nr][nc] = true;
    					}
    				}
                    // 적의 다음 1스텝 예측도 위협으로 추가
                    for (int nd = 0; nd < dirs.length; nd++) {
                        int ar = r + dirs[nd][0];
                        int ac = c + dirs[nd][1];
                        if (ar < 0 || ar >= rows || ac < 0 || ac >= cols) continue;
                        String cc2 = grid[ar][ac]; if (cc2 == null) continue;
                        if (cc2.equals("R") || cc2.equals("T") || cc2.equals("F")) continue;
                        threat[ar][ac] = true;
                    }
    			}
    		}
    	}
    	return threat;
    }

    // 방어 이동 선택: 위협이 없는 보행 가능 타일 우선, 엄폐(R/T) 인접 우대
    private static String choosePatrolMove(String[][] grid, int[] start, int[][] dirs, String[] moveCmds, boolean[][] threat) {
    	int rows = grid.length, cols = grid[0].length;
    	int[] hPos = findAllyTurret(grid);
    	int[] eCent = findEnemyCentroid(grid);
    	boolean preferVertical = preferVerticalPatrol(hPos, eCent);
    	int[] order = preferVertical ? new int[]{3,1,2,0} : new int[]{2,0,3,1}; // U,D,L,R or L,R,U,D
        boolean defenseMode = isDefenseMode(grid);
        int[] nearestToH = findNearestEnemyToH(grid);
        int[] nearestToMe = findNearestEnemyToMe(grid, start);

    	int bestIdx = -1;
    	int bestScore = Integer.MIN_VALUE;
    	for (int oi = 0; oi < order.length; oi++) {
    		int d = order[oi];
	    	int nr = start[0] + dirs[d][0]; int nc = start[1] + dirs[d][1];
    		if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) continue;
    		String cell = grid[nr][nc];
	    	if (!isWalkable(cell)) continue;
	        if (isBlockedByAllyTurretZone(grid, nr, nc)) continue;
    		int score = 0;
    		// 위협 회피
    		if (!threat[nr][nc]) score += 50; else score -= 50;
    		// 엄폐 인접 보너스
    		if (isAdjacentToCover(grid, new int[]{nr,nc}, dirs)) score += 20;
    		// 선호 축 보너스
    		boolean alongPreferred = (preferVertical && (d == 3 || d == 1)) || (!preferVertical && (d == 2 || d == 0));
    		if (alongPreferred) score += 10;
    		// 모래 패널티
    		if ("S".equals(cell)) score -= 10;
            // H로부터 이탈/방어 보너스 강화
        		if (hPos != null) {
        			int distFromH = Math.abs(nr - hPos[0]) + Math.abs(nc - hPos[1]);
        		    if (defenseMode) {
        		    	if (distFromH <= TEAM_DEF_RADIUS) score += 50; else score -= 100;
        		    } else {
        		    	if (distFromH > TEAM_DEF_RADIUS) score -= 30;
        		    }
        		}
            // 수비 모드일 때 포탑에 가장 가까운 적 차단 가중치
            if (defenseMode && nearestToH != null) {
                int distToEnemy = Math.abs(nr - nearestToH[0]) + Math.abs(nc - nearestToH[1]);
                score += Math.max(0, 6 - distToEnemy) * 3;
            }
            // 교전 추격/회피 로직
            if (nearestToMe != null) {
                int curDist = Math.abs(start[0]-nearestToMe[0]) + Math.abs(start[1]-nearestToMe[1]);
                int nextDist = Math.abs(nr-nearestToMe[0]) + Math.abs(nc-nearestToMe[1]);
                if (enemyRetreatingFlag && nextDist < curDist) score += 12; // 적이 후퇴중일 때 추격
                if (isTileInEnemyFirstStrikeLine(grid, nr, nc, dirs)) score -= 25; // 적 선공각 회피
                else if (iCanFirstStrikeFrom(grid, new int[]{nr,nc}, dirs)) score += 10; // 내가 선공 가능 시 전진 가점
            }
    		if (score > bestScore) { bestScore = score; bestIdx = d; }
    	}
    	// 모든 후보가 나빠도 S 대신 가장 나은 방향 선택
    	return bestIdx == -1 ? moveCmds[preferVertical ? 3 : 2] : moveCmds[bestIdx];
    }

    // 아군 포탑(H) 기준 -2..+2 권역(정사각형)을 벽으로 간주
    private static boolean isBlockedByAllyTurretZone(String[][] grid, int r, int c){
    	int[] h = findAllyTurret(grid);
    	if(h==null) return false;
    	int dr = Math.abs(r - h[0]);
    	int dc = Math.abs(c - h[1]);
    	return dr <= 2 && dc <= 2;
    }

    private static boolean isAdjacentToCover(String[][] grid, int[] pos, int[][] dirs) {
    	int rows = grid.length, cols = grid[0].length;
    	for (int d = 0; d < dirs.length; d++) {
    		int r = pos[0] + dirs[d][0]; int c = pos[1] + dirs[d][1];
    		if (r < 0 || r >= rows || c < 0 || c >= cols) continue;
    		String cell = grid[r][c]; if ("R".equals(cell) || "T".equals(cell)) return true;
    	}
    	return false;
    }

    private static int[] findNearestEnemyToMe(String[][] grid, int[] me){
        int rows=grid.length, cols=grid[0].length; int best=Integer.MAX_VALUE; int[] pos=null;
        for(int r=0;r<rows;r++){
            for(int c=0;c<cols;c++){
                String cell=grid[r][c]; if(cell==null) continue;
                if(cell.equals("X") || cell.startsWith("E")){
                    int d=Math.abs(me[0]-r)+Math.abs(me[1]-c);
                    if(d<best){ best=d; pos=new int[]{r,c}; }
                }
            }
        }
        return pos;
    }

    private static boolean isTileInEnemyFirstStrikeLine(String[][] grid, int r, int c, int[][] dirs){
        int rows=grid.length, cols=grid[0].length;
        for(int er=0; er<rows; er++){
            for(int ec=0; ec<cols; ec++){
                String cell=grid[er][ec]; if(cell==null) continue;
                if(cell.equals("X") || cell.startsWith("E")){
                    for(int d=0; d<4; d++){
                        int nr=er, nc=ec;
                        for(int k=1;k<=3;k++){
                            nr+=dirs[d][0]; nc+=dirs[d][1];
                            if(nr<0||nr>=rows||nc<0||nc>=cols) break;
                            String cc=grid[nr][nc]; if(cc==null) break;
                            if(cc.equals("R")||cc.equals("T")||cc.equals("F")) break;
                            if(cc.equals("M")||cc.equals("H")||cc.startsWith("M")||cc.startsWith("E")||cc.equals("X")) break;
                            if(nr==r && nc==c) return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean iCanFirstStrikeFrom(String[][] grid, int[] pos, int[][] dirs){
        LoSResult los = findFireLineOfSight(grid, pos, dirs);
        return los.canFire;
    }

    // H(아군 포탑) 위치
    private static int[] findAllyTurret(String[][] grid) {
        String sym = getAllyTurretSymbol();
        for (int r = 0; r < grid.length; r++) {
            for (int c = 0; c < grid[0].length; c++) {
                if (sym.equals(grid[r][c])) return new int[]{r,c};
            }
        }
        return null;
    }

    // 적 중심(탱크/포탑) 계산
    private static int[] findEnemyCentroid(String[][] grid) {
    	int sumR = 0, sumC = 0, cnt = 0;
    	for (int r = 0; r < grid.length; r++) {
    		for (int c = 0; c < grid[0].length; c++) {
    			String cell = grid[r][c]; if (cell == null) continue;
    			if (cell.equals("X") || cell.startsWith("E")) { sumR += r; sumC += c; cnt++; }
    		}
    	}
    	if (cnt == 0) return new int[]{grid.length/2, grid[0].length/2};
    	return new int[]{sumR / cnt, sumC / cnt};
    }

    // 수직 패트롤 선호 여부(|dx| >= |dy| → 수직 패트롤: U/D, 아니면 수평: L/R)
    private static boolean preferVerticalPatrol(int[] hPos, int[] eCent) {
    	if (hPos == null || eCent == null) return false;
    	int dx = eCent[1] - hPos[1];
    	int dy = eCent[0] - hPos[0];
    	return Math.abs(dx) >= Math.abs(dy);
    }

    // 수비 모드 판단: 포탑 체력 임계 또는 적이 포탑에 접근
    private static boolean isDefenseMode(String[][] grid) {
        int[] h = findAllyTurret(grid); if (h == null) return false;
        int hHp = getAllyTurretHp();
        if (hHp > 0 && hHp <= 30) return true;
        int[] ne = findNearestEnemyToH(grid);
        if (ne == null) return false;
        int d = Math.abs(h[0]-ne[0]) + Math.abs(h[1]-ne[1]);
        if (defenseProactiveFlag) return true;
        if (d <= PROACTIVE_DEF_DIST) return true;
        return d <= ENEMY_APPROACH_TO_H;
    }

    private static int getAllyTurretHp() {
        String sym = getAllyTurretSymbol();
        String[] h = myAllies.get(sym);
        if (h == null || h.length == 0) return 0;
        return parseIntSafe(h[0]);
    }

    private static String getAllyTurretSymbol() {
        return "H";
    }

    // H-적 최소 맨해튼 거리 계산(없으면 -1)
    private static int getMinDistEnemyToH(String[][] grid) {
    	int[] h = findAllyTurret(grid); if (h == null) return -1;
    	int rows = grid.length, cols = grid[0].length; int best = Integer.MAX_VALUE; boolean found = false;
    	for (int r = 0; r < rows; r++) {
    		for (int c = 0; c < cols; c++) {
    			String cell = grid[r][c]; if (cell == null) continue;
    			if (cell.equals("X") || cell.startsWith("E")) {
    				int d = Math.abs(h[0]-r) + Math.abs(h[1]-c);
    				if (d < best) { best = d; found = true; }
    			}
    		}
    	}
    	return found ? best : -1;
    }

    // 포탑에 가장 가까운 적 좌표
    private static int[] findNearestEnemyToH(String[][] grid) {
        int[] h = findAllyTurret(grid); if (h == null) return null;
        int rows = grid.length, cols = grid[0].length;
        int best = Integer.MAX_VALUE; int[] bestPos = null;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                String cell = grid[r][c]; if (cell == null) continue;
                if (cell.equals("X") || cell.startsWith("E")) {
                    int d = Math.abs(h[0]-r) + Math.abs(h[1]-c);
                    if (d < best) { best = d; bestPos = new int[]{r,c}; }
                }
            }
        }
        return bestPos;
    }

    // 보급소 인접 여부
    private static boolean isAdjacentToSupply(String[][] grid, int[] pos, int[][] dirs) {
        int rows = grid.length;
        int cols = grid[0].length;
            for (int d = 0; d < dirs.length; d++) {
    		int r = pos[0] + dirs[d][0];
    		int c = pos[1] + dirs[d][1];
    		if (r < 0 || r >= rows || c < 0 || c >= cols) continue;
    		if ("F".equals(grid[r][c])) return true;
    	}
    	return false;
    }

    // 시저 해독(고정 시프트 +9)
    private static String decodeCaesarShift(String cipher, int shift) {
    	StringBuilder sb = new StringBuilder();
    	for (int i = 0; i < cipher.length(); i++) {
    		char ch = cipher.charAt(i);
    		if (ch >= 'A' && ch <= 'Z') {
    			int v = ch - 'A';
    			int nv = (v + shift) % 26;
    			sb.append((char)('A' + nv));
    		} else {
    			sb.append(ch);
    		}
    	}
    	return sb.toString();
    }

    // 적 접근 여부(맨해튼 거리 <= 4 내에 적 존재)
    private static boolean isEnemyApproaching(String[][] grid, int[] myPos) {
    	int rows = grid.length, cols = grid[0].length;
    	for (int r = 0; r < rows; r++) {
    		for (int c = 0; c < cols; c++) {
    			String cell = grid[r][c]; if (cell == null) continue;
    			if (cell.equals("X") || cell.startsWith("E")) {
    				int dist = Math.abs(r - myPos[0]) + Math.abs(c - myPos[1]);
    				if (dist <= 4) return true;
    			}
    		}
    	}
    	return false;
    }

    // 팀 메가포탄 추정 합계(M, M1, M2 등에서 정보가 있으면 합산; 없으면 0으로 간주)
    private static int estimateTeamMega() {
    	int total = 0;
    	for (String key : myAllies.keySet()) {
    		String[] val = myAllies.get(key);
    		if (val == null) continue;
    		// M(자신): [hp, dir, normal, mega]
    		// 기타 아군은 hp만 있을 수 있음
    		if (key.equals("M") || key.startsWith("M")) {
    			if (val.length >= 4) total += parseIntSafe(val[3]);
    		}
    	}
    	return total;
    }

    // 보급소 인접 타일들 중 하나를 목표로 A* 이동 경로 산출(가중치 적용)
    private static Queue<String> aStarToSupplyAdjacency(String[][] grid, int[] start, int[][] dirs, String[] moveCmds, boolean[][] threat, int turn) {
    	int rows = grid.length;
    	int cols = grid[0].length;
    	boolean[][] goals = new boolean[rows][cols];
        // 가장 가까운 F를 찾고, F 인접 4칸 중 클라이언트별 슬롯 선호
        int[] fPos = findNearestSupply(grid, start);
		int preferredSlot = (getClientIndex() + (turn & 3)) % 4; // 슬롯 회전으로 충돌 분산
        if (fPos != null) {
            // 선호 슬롯 우선, 불가시 나머지 슬롯 순으로 허용
            int[] slotOrder = new int[]{preferredSlot, (preferredSlot+1)%4, (preferredSlot+2)%4, (preferredSlot+3)%4};
            for (int si = 0; si < 4; si++) {
                int d = slotOrder[si];
                int nr = fPos[0] + dirs[d][0];
                int nc = fPos[1] + dirs[d][1];
                if (nr<0||nr>=rows||nc<0||nc>=cols) continue;
                if (isWalkable(grid[nr][nc]) || (nr==start[0]&&nc==start[1])) goals[nr][nc] = true;
                if (si==0 && goals[nr][nc]) break; // 선호 슬롯 가능하면 그것만 목표로
            }
        }
        // 근처에 F가 없거나 선호 슬롯이 모두 불가하면 기존 전역 인접 허용
        boolean any=false; for(int r=0;r<rows;r++){ for(int c=0;c<cols;c++){ if(goals[r][c]) { any=true; break; } } if(any) break; }
        if(!any){
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (!"F".equals(grid[r][c])) continue;
            for (int d = 0; d < dirs.length; d++) {
                int nr = r + dirs[d][0];
                int nc = c + dirs[d][1];
                        if (nr<0||nr>=rows||nc<0||nc>=cols) continue;
                        if (isWalkable(grid[nr][nc]) || (nr==start[0]&&nc==start[1])) goals[nr][nc] = true;
                    }
                }
            }
        }
    	int[] hPos = findAllyTurret(grid);
    	return aStarToGoals(grid, start, goals, dirs, moveCmds, threat, hPos);
    }

    // 보행 가능 판정: G/S만 허용(시작 위치 M은 예외 허용)
    private static boolean isWalkable(String cell) {
    	return "G".equals(cell) || "S".equals(cell);
    }

    // F 인접 유지(오빗): 시계 방향으로 인접 4칸을 돌며 S를 피하고 충돌을 최소화
    private static String chooseSupplyOrbitMove(String[][] grid, int[] start, int[][] dirs, String[] moveCmds){
        int rows=grid.length, cols=grid[0].length; int[] f=findNearestSupply(grid, start); if(f==null) return moveCmds[0];
        // 현재가 F 인접이면 그 이웃 중 다음 각을 우선
        int[] order=new int[]{0,1,2,3}; int best=-1; int bestScore=Integer.MIN_VALUE;
        for(int oi=0; oi<4; oi++){
            int d=order[oi]; int nr=start[0]+dirs[d][0], nc=start[1]+dirs[d][1];
            if(nr<0||nr>=rows||nc<0||nc>=cols) continue; String cell=grid[nr][nc];
            if(!isWalkable(cell)) continue; if(isBlockedByAllyTurretZone(grid,nr,nc)) continue;
            // F와의 인접성 유지 보너스
            int score=0; boolean keepAdj=false; for(int k=0;k<4;k++){ int ar=nr+dirs[k][0], ac=nc+dirs[k][1]; if(ar<0||ar>=rows||ac<0||ac>=cols) continue; if("F".equals(grid[ar][ac])){ keepAdj=true; break; } }
            if(keepAdj) score+=10; else score-=50; // 인접 끊기면 강한 패널티
            if("S".equals(cell)) score-=5; // 모래 약한 패널티
            // 최근 교착이면 직전 이동 방향을 약하게 회피
            if (recentStuck && lastMoveDir==d) score -= 8;
            if(score>bestScore){ bestScore=score; best=d; }
        }
        return best==-1? moveCmds[0] : moveCmds[best];
    }

    private static int[] findNearestSupply(String[][] grid, int[] start){
        int rows=grid.length, cols=grid[0].length; int best=Integer.MAX_VALUE; int[] pos=null;
        for(int r=0;r<rows;r++){
            for(int c=0;c<cols;c++){
                if("F".equals(grid[r][c])){
                    int d=Math.abs(r-start[0])+Math.abs(c-start[1]);
                    if(d<best){ best=d; pos=new int[]{r,c}; }
                }
            }
        }
        return pos;
    }

    // F 인접칸까지의 최단 맨해튼 거리(이동 가능 칸만 목표). 없으면 -1
    private static int distanceToNearestSupplyAdjacency(String[][] grid, int[] start, int[][] dirs){
        int[] f = findNearestSupply(grid, start); if (f==null) return -1; int best=Integer.MAX_VALUE;
        int rows=grid.length, cols=grid[0].length;
        for(int d=0; d<4; d++){
            int nr=f[0]+dirs[d][0], nc=f[1]+dirs[d][1];
            if(nr<0||nr>=rows||nc<0||nc>=cols) continue;
            if (isWalkable(grid[nr][nc]) || (nr==start[0]&&nc==start[1])){
                int dist = Math.abs(start[0]-nr)+Math.abs(start[1]-nc);
                if (dist<best) best=dist;
            }
        }
        return best==Integer.MAX_VALUE? -1: best;
    }

    // 최근접 적(X 또는 E*)까지의 맨해튼 거리. 없으면 -1
    private static int distanceToNearestEnemy(String[][] grid, int[] start){
        int rows=grid.length, cols=grid[0].length; int best=Integer.MAX_VALUE; boolean found=false;
        for(int r=0;r<rows;r++){
            for(int c=0;c<cols;c++){
                String cell=grid[r][c]; if(cell==null) continue;
                if (cell.equals("X") || cell.startsWith("E")){
                    int d=Math.abs(start[0]-r)+Math.abs(start[1]-c);
                    if (d<best){ best=d; found=true; }
                }
            }
        }
        return found? best: -1;
    }

    private static int getClientIndex(){
        if (ARGS==null||ARGS.isEmpty()) return 0;
        int v=0; for(int i=0;i<ARGS.length();i++){ char ch=ARGS.charAt(i); if(ch>='0'&&ch<='9'){ v=v*10+(ch-'0'); } else break; }
        return v;
    }

    private static boolean isMySupplyTurn(int turn){ int idx=getClientIndex()%3; return (turn%3)==(idx%3); }

    // A*: 목표 집합 도달 시 이동 명령 큐 반환(가중치 적용)
    private static Queue<String> aStarToGoals(String[][] grid, int[] start, boolean[][] goals, int[][] dirs, String[] moveCmds, boolean[][] threat, int[] hPos) {
    	int rows = grid.length;
    	int cols = grid[0].length;
    	PriorityQueue<AStarNode> open = new PriorityQueue<>(Comparator.comparingInt(n -> n.fCost));
    	Map<String, Integer> bestG = new HashMap<>();
    	Map<String, String> cameFrom = new HashMap<>();
    	Map<String, Integer> cameDir = new HashMap<>();

    	String startKey = start[0] + "," + start[1];
    	open.offer(new AStarNode(start[0], start[1], 0, heuristicToGoals(start[0], start[1], goals)));
    	bestG.put(startKey, 0);

    	while (!open.isEmpty()) {
    		AStarNode cur = open.poll();
    		if (goals[cur.r][cur.c]) {
    			return reconstructPath(cameFrom, cameDir, cur.r, cur.c, startKey, moveCmds);
    		}
            for (int d = 0; d < dirs.length; d++) {
    			int nr = cur.r + dirs[d][0];
    			int nc = cur.c + dirs[d][1];
    			if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) continue;
    			String cell = grid[nr][nc];
    			if (cell == null) continue;
                if (!isWalkable(cell)) continue;
                if (isBlockedByAllyTurretZone(grid, nr, nc)) continue;
    			int stepCost = weightedTileCost(grid, nr, nc, threat, hPos);
    			int newG = cur.gCost + stepCost;
                String key = nr + "," + nc;
    			Integer prevBest = bestG.get(key);
    			if (prevBest == null || newG < prevBest) {
    				bestG.put(key, newG);
    				cameFrom.put(key, cur.r + "," + cur.c);
    				cameDir.put(key, d);
    				int h = heuristicToGoals(nr, nc, goals);
    				open.offer(new AStarNode(nr, nc, newG, newG + h));
    			}
    		}
    	}
        return new LinkedList<>();
    }

    private static int heuristicToGoals(int r, int c, boolean[][] goals) {
    	int rows = goals.length, cols = goals[0].length;
    	int best = Integer.MAX_VALUE;
    	for (int i = 0; i < rows; i++) {
    		for (int j = 0; j < cols; j++) {
    			if (!goals[i][j]) continue;
    			int d = Math.abs(i - r) + Math.abs(j - c);
    			if (d < best) best = d;
    		}
    	}
    	return best == Integer.MAX_VALUE ? 0 : best;
    }

    // 가중치 계산: 기본 1 + 위협 + 모래 + 라인 유지(과도 이탈)
    private static int weightedTileCost(String[][] grid, int r, int c, boolean[][] threat, int[] hPos) {
    	int cost = 1;
    	if (threat != null && threat[r][c]) cost += 50;
    	if ("S".equals(grid[r][c])) cost += 10;
    	if (hPos != null) {
    		int dist = Math.abs(r - hPos[0]) + Math.abs(c - hPos[1]);
    		if (defenseProactiveFlag) {
    			if (dist > TEAM_DEF_RADIUS) cost += 60; // 수비 시 강한 이탈 패널티
    		} else {
    			if (dist > TEAM_DEF_RADIUS) cost += 30;
    		}
    	}
    	return cost;
    }

    private static Queue<String> reconstructPath(Map<String, String> cameFrom, Map<String, Integer> cameDir, int endR, int endC, String startKey, String[] moveCmds) {
    	LinkedList<String> path = new LinkedList<>();
    	String curKey = endR + "," + endC;
    	while (!curKey.equals(startKey)) {
    		Integer dir = cameDir.get(curKey);
    		if (dir == null) break;
    		path.addFirst(moveCmds[dir]);
    		curKey = cameFrom.get(curKey);
    		if (curKey == null) break;
    	}
    	return path;
    }

    private static class AStarNode {
    	int r, c;
    	int gCost;
    	int fCost;
    	AStarNode(int r, int c, int gCost, int fCost) { this.r=r; this.c=c; this.gCost=gCost; this.fCost=fCost; }
    }

    // (제거됨) 기존 BFS 헬퍼 클래스는 A* 전환으로 사용하지 않음
    
	////////////////////////////////////
	// 알고리즘 함수/메서드 부분 구현 끝
	////////////////////////////////////
    
    ///////////////////////////////
    // 메인 프로그램 통신 메서드 정의
    ///////////////////////////////

    // 메인 프로그램 연결 및 초기화
    private static String init(String nickname) {
        try {
            System.out.println("[STATUS] Trying to connect to " + HOST + ":" + PORT + "...");
            socket = new Socket();
            socket.connect(new InetSocketAddress(HOST, PORT));
            System.out.println("[STATUS] Connected");
            String initCommand = "INIT " + nickname;

            return submit(initCommand);
        } catch (Exception e) {
            System.out.println("[ERROR] Failed to connect. Please check if the main program is waiting for connection.");
            e.printStackTrace();
            return null;
        }
    }

    // 메인 프로그램으로 데이터(명령어) 전송
    private static String submit(String stringToSend) {
        try {
            OutputStream os = socket.getOutputStream();
            String sendData = ARGS + stringToSend + " ";
            os.write(sendData.getBytes("UTF-8"));
            os.flush();

            return receive();
        } catch (Exception e) {
            System.out.println("[ERROR] Failed to send data. Please check if connection to the main program is valid.");
            e.printStackTrace();
        }
        return null;
    }

    // 메인 프로그램으로부터 데이터 수신
    private static String receive() {
        try {
            InputStream is = socket.getInputStream();
            byte[] bytes = new byte[1024];
            int length = is.read(bytes);
            if (length <= 0) {
                System.out.println("[STATUS] No receive data from the main program.");
                close();
                return null;
            }

            String gameData = new String(bytes, 0, length, "UTF-8");
            if (gameData.length() > 0 && gameData.charAt(0) >= '1' && gameData.charAt(0) <= '9') {
                return gameData;
            }

            System.out.println("[STATUS] No receive data from the main program.");
            close();
            return null;
        } catch (Exception e) {
            System.out.println("[ERROR] Failed to receive data. Please check if connection to the main program is valid.");
            e.printStackTrace();
        }
        return null;
    }

    // 연결 해제
    private static void close() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                System.out.println("[STATUS] Connection closed");
            }
        } catch (Exception e) {
            System.out.println("[ERROR] Network connection has been corrupted.");
            e.printStackTrace();
        }
    }

	///////////////////////////////
	// 입력 데이터 파싱
	///////////////////////////////

    // 입력 데이터를 파싱하여 각각의 배열/맵에 저장
    private static void parseData(String gameData) {
        // 입력 데이터를 행으로 나누기
        String[] gameDataRows = gameData.split("\n");
        int rowIndex = 0;

        // 첫 번째 행 데이터 읽기
        String[] header = gameDataRows[rowIndex].split(" ");
        int mapHeight = header.length >= 1 ? Integer.parseInt(header[0]) : 0; // 맵의 세로 크기
        int mapWidth = header.length >= 2 ? Integer.parseInt(header[1]) : 0;  // 맵의 가로 크기
        int numOfAllies = header.length >= 3 ? Integer.parseInt(header[2]) : 0;  // 아군의 수
        int numOfEnemies = header.length >= 4 ? Integer.parseInt(header[3]) : 0;  // 적군의 수
        int numOfCodes = header.length >= 5 ? Integer.parseInt(header[4]) : 0;  // 암호문의 수
        rowIndex++;

        // 기존의 맵 정보를 초기화하고 다시 읽어오기
        mapData = new String[mapHeight][mapWidth];
        for (int i = 0; i < mapHeight; i++) {
            String[] col = gameDataRows[rowIndex + i].split(" ");
            for (int j = 0; j < col.length; j++) {
                mapData[i][j] = col[j];
            }
        }
        rowIndex += mapHeight;

        // 기존의 아군 정보를 초기화하고 다시 읽어오기
        myAllies.clear();
        for (int i = rowIndex; i < rowIndex + numOfAllies; i++) {
            String[] ally = gameDataRows[i].split(" ");
            String allyName = ally.length >= 1 ? ally[0] : "-";
            String[] allyData = new String[ally.length - 1];
            System.arraycopy(ally, 1, allyData, 0, ally.length - 1);
            myAllies.put(allyName, allyData);
        }
        rowIndex += numOfAllies;

        // 기존의 적군 정보를 초기화하고 다시 읽어오기
        enemies.clear();
        for (int i = rowIndex; i < rowIndex + numOfEnemies; i++) {
            String[] enemy = gameDataRows[i].split(" ");
            String enemyName = enemy.length >= 1 ? enemy[0] : "-";
            String[] enemyData = new String[enemy.length - 1];
            System.arraycopy(enemy, 1, enemyData, 0, enemy.length - 1);
            enemies.put(enemyName, enemyData);
        }
        rowIndex += numOfEnemies;

        // 기존의 암호문 정보를 초기화하고 다시 읽어오기
        codes = new String[numOfCodes];
        for (int i = 0; i < numOfCodes; i++) {
            codes[i] = gameDataRows[rowIndex + i];
        }
    }

    // 파싱한 데이터를 화면에 출력
    private static void printData(String gameData) {
        System.out.printf("\n----------입력 데이터----------\n%s\n----------------------------\n", gameData);

        System.out.printf("\n[맵 정보] (%d x %d)\n", mapData.length, mapData[0].length);
        for (String[] row : mapData) {
            for (String cell : row) {
                System.out.printf("%s ", cell);
            }
            System.out.println();
        }

        System.out.printf("\n[아군 정보] (아군 수: %d)\n", myAllies.size());
        for (String key : myAllies.keySet()) {
            String[] value = myAllies.get(key);
            if (key.equals("M")) {
                System.out.printf("M (내 탱크) - 체력: %s, 방향: %s, 보유한 일반 포탄: %s, 보유한 메가 포탄: %s\n",
                        value[0], value[1], value[2], value[3]);
            } else if (key.equals("H")) {
                System.out.printf("H (아군 포탑) - 체력: %s\n", value[0]);
            } else {
                System.out.printf("%s (아군 탱크) - 체력: %s\n", key, value[0]);
            }
        }

        System.out.printf("\n[적군 정보] (적군 수: %d)\n", enemies.size());
        for (String key : enemies.keySet()) {
            String[] value = enemies.get(key);
            if (key.equals("X")) {
                System.out.printf("X (적군 포탑) - 체력: %s\n", value[0]);
            } else {
                System.out.printf("%s (적군 탱크) - 체력: %s\n", key, value[0]);
            }
        }

        System.out.printf("\n[암호문 정보] (암호문 수: %d)\n", codes.length);
        for (String code : codes) {
            System.out.println(code);
        }
    }
}
