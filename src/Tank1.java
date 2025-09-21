import java.net.*;
import java.io.*;
import java.util.*;

public class Tank1 {
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
    private static int enemyApproachStreak = 0; // 적 접근 연속 턴 수
    // 보급 응급 상황 추적
    private static int[] positionHistory = new int[6]; // 최근 3턴 위치 기록 [r1,c1,r2,c2,r3,c3]
    private static int stuckTurnCount = 0; // 연속 교착 턴 수
    private static boolean emergencySupplyMode = false; // 응급 보급 모드
    private static int lastSupplyTurn = -10; // 마지막 보급 시도 턴
    
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
        // 보급 응급 임계값
        final int STALEMATE_THRESHOLD = 3; // 교착 감지 턴수
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
            int myShell = me != null && me.length >= 3 ? parseIntSafe(me[2]) : 0; // 일반 포탄
            int myMega = me != null && me.length >= 4 ? parseIntSafe(me[3]) : 0;

            // 선제 수비 모드 추세 갱신
            int curMinDistToH = getMinDistEnemyToH(mapData);
            if (curMinDistToH < prevMinDistToH) approachTrendCount++; else approachTrendCount = 0;
            prevMinDistToH = curMinDistToH;
            defenseProactiveFlag = (curMinDistToH > -1 && curMinDistToH <= PROACTIVE_DEF_DIST) || (approachTrendCount >= 2);
            // 적 후퇴 추세 갱신(나 기준)
            int curNearestEnemyDist = distanceToNearestEnemy(mapData, myPos);
            enemyRetreatingFlag = (lastNearestEnemyDist != -1 && curNearestEnemyDist > lastNearestEnemyDist);
            if (lastNearestEnemyDist != -1 && curNearestEnemyDist != -1 && curNearestEnemyDist < lastNearestEnemyDist) enemyApproachStreak++; else enemyApproachStreak = 0;

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
                // 방어 위기 시: 안전 여유가 있으면 보급 해독 허용, 아니면 수비 라인 우선
                boolean earlyPhase = turn < EARLY_SUPPLY_TURNS;
                boolean needMega = (myMega < MEGA_TARGET_PER_TANK) && (estimateTeamMega() < TEAM_MEGA_MIN_RESERVE || earlyPhase) && (myMega < 10);
                boolean codesAvailable = (codes != null && codes.length > 0);

                // 방어 위기 시: 안전 여유가 있으면 보급 해독 허용, 아니면 수비 라인 우선
                if (isDefenseEmergency(mapData)) {
                    if (codesAvailable && (myMega < MEGA_TARGET_PER_TANK) && canResupplyWhileDefending(mapData)) {
                        String cipher = codes[0].trim();
                        String plain = decodeCaesarShift(cipher, 9);
                        output = "G " + plain;
                        System.out.println("[T1-SUPPLY-DEF] Decoding under defense: " + cipher + " -> " + plain);
                    } else {
                        output = chooseDefenseLineMove(mapData, myPos, DIRS, MOVE_CMDS, buildThreatMap(mapData, DIRS));
                    }
                } else {
                    // MAX 2발 제한: 내 메가탄이 2 미만일 때만 해독
                    if (codesAvailable && (myMega < MEGA_TARGET_PER_TANK) && (emergencySupplyMode || needMega || enemyApproachStreak < 2)) {
                    String cipher = codes[0].trim();
                    String plain = decodeCaesarShift(cipher, 9);
                    output = "G " + plain;
                    System.out.println("[T1-SUPPLY] Decoding: " + cipher + " -> " + plain + " (Emergency: " + emergencySupplyMode + ")");
                } else {
                        // 보급 필요 없음: 오빗 중단, 즉시 전진/교전 로직으로
                        output = chooseTeamCoordinatedMove(mapData, myPos, DIRS, MOVE_CMDS, buildThreatMap(mapData, DIRS));
                    }
                }
            } else {
            	// 3) 이동: 응급 보급 시스템 적용 - 교착 감지 및 우선순위 오버라이드
            	boolean[][] threat = buildThreatMap(mapData, DIRS);
            	boolean approaching = isEnemyApproaching(mapData, myPos);
                // 최근 교착 여부 갱신
                if (lastR != -1 && lastC != -1 && lastWasMove && myPos[0]==lastR && myPos[1]==lastC) {
                	recentStuck = true;
                } else {
                	recentStuck = false;
                }

                // 교착 상황 및 보급 응급 상황 감지
                boolean isStalemate = updateStalemate(myPos, turn);
                boolean earlyPhase = turn < EARLY_SUPPLY_TURNS;
                boolean codesAvailable = (codes != null && codes.length > 0);
                boolean myTurnForSupply = isMySupplyTurn(turn);
                int distToF = distanceToNearestSupplyAdjacency(mapData, myPos, DIRS);
                int distToEnemy = distanceToNearestEnemy(mapData, myPos);
                int teamMegaTotal = estimateTeamMega();

                // 방어 위기 시: 안전 여유가 충분하고 보급 미달이면 보급 인접 이동 허용, 아니면 수비 우선
                if (isDefenseEmergency(mapData)) {
                    if (myMega < MEGA_TARGET_PER_TANK && canMoveToSupplyWhileDefending(mapData)) {
                        Queue<String> pathToF = aStarToSupplyAdjacency(mapData, myPos, DIRS, MOVE_CMDS, threat, turn);
                        if (pathToF != null && !pathToF.isEmpty()) {
                            output = pathToF.poll();
                        } else {
                            output = chooseDefenseLineMove(mapData, myPos, DIRS, MOVE_CMDS, threat);
                        }
                    } else {
                        output = chooseDefenseLineMove(mapData, myPos, DIRS, MOVE_CMDS, threat);
                    }
                } else {
                // 새로운 보급 우선순위 계산
                boolean supplyPriority = calculateSupplyPriority(myShell, myMega, teamMegaTotal, isStalemate,
                                                                turn, codesAvailable, myTurnForSupply, earlyPhase,
                                                                distToF, distToEnemy, approaching, threat[myPos[0]][myPos[1]]);

                    // 극초반 강제 보급: MAX 2발 미만일 때만 이동
                    if (turn < EARLY_SUPPLY_TURNS && myMega < MEGA_TARGET_PER_TANK) {
                    Queue<String> pathToF = aStarToSupplyAdjacency(mapData, myPos, DIRS, MOVE_CMDS, threat, turn);
                        if ((pathToF == null || pathToF.isEmpty()) || stuckTurnCount >= STALEMATE_THRESHOLD) {
                            Queue<String> reroute = rerouteToAllySideSupply(mapData, myPos, DIRS, MOVE_CMDS, threat);
                            output = (reroute != null && !reroute.isEmpty()) ? reroute.poll() : chooseSupplyOrbitMove(mapData, myPos, DIRS, MOVE_CMDS);
                        } else {
                            output = pathToF.poll();
                        }
                    } else if (supplyPriority) {
                        Queue<String> pathToF = aStarToSupplyAdjacency(mapData, myPos, DIRS, MOVE_CMDS, threat, turn);
                        if ((pathToF == null || pathToF.isEmpty()) || stuckTurnCount >= STALEMATE_THRESHOLD) {
                            Queue<String> reroute = rerouteToAllySideSupply(mapData, myPos, DIRS, MOVE_CMDS, threat);
                            output = (reroute != null && !reroute.isEmpty()) ? reroute.poll() : chooseSupplyOrbitMove(mapData, myPos, DIRS, MOVE_CMDS);
                        } else {
                            output = pathToF.poll();
                        }
                    if (emergencySupplyMode) {
                        lastSupplyTurn = turn; // 응급 보급 시도 기록
                        System.out.println("[T1-EMERGENCY] Emergency supply mode activated! Risk level high.");
                    }
                } else {
                    emergencySupplyMode = false; // 응급 모드 해제
                        // 축 포위 포지셔닝: 교착 또는 적 근접 시 우선 시도
                        String axisMove = null;
                        if (stuckTurnCount >= STALEMATE_THRESHOLD || enemyNearby(mapData, myPos)) {
                            axisMove = moveToAxisEncirclement(mapData, myPos, DIRS, MOVE_CMDS, threat);
                        }
                        if (axisMove != null) {
                            output = axisMove;
                        } else if (hasLocalHpSuperiority(mapData, myPos, 3)) {
                            // 체력 우위 시 적극 전진 공격 우선
                            String act = chooseAggressiveAdvanceMove(mapData, myPos, DIRS, MOVE_CMDS);
                            output = (act != null) ? act : chooseTeamCoordinatedMove(mapData, myPos, DIRS, MOVE_CMDS, threat);
                        } else if (distToEnemy < 0 || distToEnemy > 8) {
                            // 주변 적 부재/원거리: 즉시 전진 탐색
                            output = chooseRandomAdvanceMove(mapData, myPos, DIRS, MOVE_CMDS, turn);
                        } else {
                    output = chooseTeamCoordinatedMove(mapData, myPos, DIRS, MOVE_CMDS, threat);
                        }
                    }
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

                // 안전 게릴라 로직: 내가 선공 가능한지 체크
                boolean canFirstStrike = iCanFirstStrikeFrom(grid, new int[]{nr,nc}, dirs);

                // 추격 대상 외 다른 적들의 선공각에 있는지 체크
                boolean inOtherEnemyRange = isInOtherEnemiesFirstStrikeLine(grid, nr, nc, nearestToMe, dirs);

                if (canFirstStrike && !inOtherEnemyRange) {
                    // 안전한 선공 기회 = 최우선
                    score += 70;
                } else if (enemyRetreatingFlag && nextDist < curDist) {
                    // 추격 상황
                    if (!inOtherEnemyRange) {
                        score += 50; // 안전한 추격
                    } else {
                        score += 15; // 위험하지만 추격
                    }
                } else if (inOtherEnemyRange) {
                    // 다른 적들의 선공각 = 회피
                    score -= 35;
                } else if (canFirstStrike) {
                    // 선공 가능하지만 다른 위험 있을 수 있음
                    score += 25;
                }
            }
    		if (score > bestScore) { bestScore = score; bestIdx = d; }
    	}
    	// 모든 후보가 나빠도 S 대신 가장 나은 방향 선택
    	return bestIdx == -1 ? moveCmds[preferVertical ? 3 : 2] : moveCmds[bestIdx];
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

    private static boolean iCanFirstStrikeFrom(String[][] grid, int[] pos, int[][] dirs){
        LoSResult los = findFireLineOfSight(grid, pos, dirs);
        return los.canFire;
    }

    private static int getMyHp(){ String[] me = myAllies.get("M"); return (me!=null&&me.length>=1)? parseIntSafe(me[0]) : 0; }
    private static int[] getMyAmmo(){ String[] me=myAllies.get("M"); int ns=(me!=null&&me.length>=3)? parseIntSafe(me[2]):0; int ms=(me!=null&&me.length>=4)? parseIntSafe(me[3]):0; return new int[]{ns,ms}; }

    // 교전 개시 여부 판단: 탄약, 국지 체력 우위, 기본 체력/거리 고려
    private static boolean shouldEngageNow(String[][] grid, int[] myPos){
        int[] ammo = getMyAmmo(); boolean haveAmmo = (ammo[0]>0 || ammo[1]>0);
        if(!haveAmmo) return false;
        if(hasLocalHpSuperiority(grid, myPos, 3)) return true;
        int myHp = getMyHp(); int[] enemy = findNearestEnemyToMe(grid, myPos);
        int dist = (enemy==null)? Integer.MAX_VALUE : Math.abs(myPos[0]-enemy[0])+Math.abs(myPos[1]-enemy[1]);
        // 근접 교전에서 체력 충분 시 허용
        return (dist <= 4 && myHp >= 40);
    }

    // 특정 타겟 좌표를 사거리 3 직선으로 직접 조준 가능한지
    private static boolean canShootTargetFrom(String[][] grid, int[] from, int[] target, int[][] dirs){
        if (from==null || target==null) return false;
        for(int d=0; d<dirs.length; d++){
            int r=from[0], c=from[1];
            for(int k=1;k<=3;k++){
                r+=dirs[d][0]; c+=dirs[d][1];
                if(r<0||r>=grid.length||c<0||c>=grid[0].length) break;
                String cell=grid[r][c]; if(cell==null) break;
                if (r==target[0] && c==target[1]) return true;
                if("R".equals(cell)||"T".equals(cell)||"F".equals(cell)) break;
                if("M".equals(cell)||"H".equals(cell)||cell.startsWith("M")||cell.startsWith("E")||"X".equals(cell)) break;
            }
        }
        return false;
    }

    // BFS로 타겟을 사격 가능해지기까지 필요한 최소 이동 턴 계산(격자식, 가중치 미사용)
    private static int minTurnsToShootTarget(String[][] grid, int[] start, int[] target, int[][] dirs, int maxDepth, boolean ignoreAllyTurretZone){
        if (start==null || target==null) return Integer.MAX_VALUE;
        if (canShootTargetFrom(grid, start, target, dirs)) return 0;
        int rows=grid.length, cols=grid[0].length;
        boolean[][] visited=new boolean[rows][cols];
        ArrayDeque<int[]> q=new ArrayDeque<>();
        q.offer(new int[]{start[0], start[1], 0});
        visited[start[0]][start[1]] = true;
        while(!q.isEmpty()){
            int[] cur=q.poll();
            int r=cur[0], c=cur[1], d=cur[2];
            if (d>=maxDepth) continue;
            for(int i=0;i<4;i++){
                int nr=r+dirs[i][0], nc=c+dirs[i][1];
                if(nr<0||nr>=rows||nc<0||nc>=cols) continue;
                if(visited[nr][nc]) continue;
                String cell=grid[nr][nc]; if(cell==null) continue;
                if(!isWalkable(cell)) continue;
                if(!ignoreAllyTurretZone && isBlockedByAllyTurretZone(grid,nr,nc)) continue;
                visited[nr][nc]=true;
                int nd=d+1;
                int[] np = new int[]{nr,nc};
                if (canShootTargetFrom(grid, np, target, dirs)) return nd;
                q.offer(new int[]{nr,nc,nd});
            }
        }
        return Integer.MAX_VALUE;
    }

    // 추격 대상을 제외한 다른 적들의 선공각 체크
    private static boolean isInOtherEnemiesFirstStrikeLine(String[][] grid, int r, int c, int[] targetEnemy, int[][] dirs) {
        int rows = grid.length, cols = grid[0].length;
        for(int er = 0; er < rows; er++) {
            for(int ec = 0; ec < cols; ec++) {
                // 추격 대상은 제외
                if (targetEnemy != null && er == targetEnemy[0] && ec == targetEnemy[1]) continue;

                String cell = grid[er][ec];
                if(cell == null) continue;
                if(cell.equals("X") || cell.startsWith("E")) {
                    for(int d = 0; d < 4; d++) {
                        int nr = er, nc = ec;
                        for(int k = 1; k <= 3; k++) {
                            nr += dirs[d][0]; nc += dirs[d][1];
                            if(nr < 0 || nr >= rows || nc < 0 || nc >= cols) break;
                            String cc = grid[nr][nc];
                            if(cc == null) break;
                            if(cc.equals("R") || cc.equals("T") || cc.equals("F")) break;
                            if(cc.equals("M") || cc.equals("H") || cc.startsWith("M") || cc.startsWith("E") || cc.equals("X")) break;
                            if(nr == r && nc == c) return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // ===== 팀 협동 시스템 =====

    // 역할 정의
    enum TankRole {
        AGGRESSOR,    // 주공격수: 가장 적에게 가까운 탱크
        FLANKER,      // 측면공격: 적의 측면/후방 담당
        SUPPORTER,    // 지원수: 상황에 따라 지원 또는 H 보호
        INTERCEPTOR,  // 차단수: 적의 도주로 차단
        SUPPLIER      // 보급수: 응급 보급이 필요한 탱크
    }

    // 팀 상황 분석 클래스
    private static class TeamSituationAnalyzer {
        // 아군 위치 파싱
        static int[] getMyPosition(String[][] grid) {
            for (int r = 0; r < grid.length; r++) {
                for (int c = 0; c < grid[0].length; c++) {
                    if ("M".equals(grid[r][c])) return new int[]{r, c};
                }
            }
            return null;
        }

        static int[] getAllyPosition(String[][] grid, String allySymbol) {
            for (int r = 0; r < grid.length; r++) {
                for (int c = 0; c < grid[0].length; c++) {
                    if (allySymbol.equals(grid[r][c])) return new int[]{r, c};
                }
            }
            return null;
        }

        // 거리 계산
        static int getDistance(int[] pos1, int[] pos2) {
            if (pos1 == null || pos2 == null) return Integer.MAX_VALUE;
            return Math.abs(pos1[0] - pos2[0]) + Math.abs(pos1[1] - pos2[1]);
        }

        // 교전 상황 판단 (적과 3칸 이내 = 교전 중)
        static boolean isAllyEngaging(int[] allyPos, int[] enemyPos) {
            return getDistance(allyPos, enemyPos) <= 3;
        }

        // 적을 중심으로 한 각도 계산 (0~360도)
        static double getAngleToEnemy(int[] pos, int[] enemyPos) {
            if (pos == null || enemyPos == null) return -1;
            int dx = pos[1] - enemyPos[1];
            int dy = pos[0] - enemyPos[0];
            return Math.toDegrees(Math.atan2(dy, dx));
        }

        // 포위망 분석: 팀이 적 주변에 얼마나 분산되어 있는지
        static double[] getTeamAnglesAroundEnemy(String[][] grid) {
            int[] enemyPos = findNearestEnemyToMe(grid, getMyPosition(grid));
            if (enemyPos == null) return new double[0];

            int[] myPos = getMyPosition(grid);
            int[] ally2Pos = getAllyPosition(grid, "M2");
            int[] ally3Pos = getAllyPosition(grid, "M3");

            double[] angles = new double[3];
            angles[0] = getAngleToEnemy(myPos, enemyPos);
            angles[1] = getAngleToEnemy(ally2Pos, enemyPos);
            angles[2] = getAngleToEnemy(ally3Pos, enemyPos);

            return angles;
        }

        // 아군 중 누군가 교전 중인지 확인
        static boolean isAnyAllyEngaging(String[][] grid) {
            int[] enemyPos = findNearestEnemyToMe(grid, getMyPosition(grid));
            if (enemyPos == null) return false;

            int[] ally2Pos = getAllyPosition(grid, "M2");
            int[] ally3Pos = getAllyPosition(grid, "M3");

            return isAllyEngaging(ally2Pos, enemyPos) || isAllyEngaging(ally3Pos, enemyPos);
        }
    }

    // 동적 역할 결정 시스템
    private static class DynamicRoleAssigner {
        static TankRole determineMyRole(String[][] grid) {
            int[] myPos = TeamSituationAnalyzer.getMyPosition(grid);
            int[] ally2Pos = TeamSituationAnalyzer.getAllyPosition(grid, "M2");
            int[] ally3Pos = TeamSituationAnalyzer.getAllyPosition(grid, "M3");
            int[] enemyPos = findNearestEnemyToMe(grid, myPos);

            if (enemyPos == null) return TankRole.SUPPORTER;

            // 0. 최우선: 응급 보급 상황이면 SUPPLIER 역할
            if (emergencySupplyMode) {
                return TankRole.SUPPLIER;
            }

            // 1. 거리 기반 역할 결정: 적에게 가장 가까우면 AGGRESSOR
            if (isClosestToEnemy(myPos, ally2Pos, ally3Pos, enemyPos)) {
                return TankRole.AGGRESSOR;
            }

            // 2. 교전 상황 분석: 다른 아군이 교전 중이고 내가 측면 공격 가능하면 FLANKER
            if (TeamSituationAnalyzer.isAnyAllyEngaging(grid) && canFlank(myPos, enemyPos, ally2Pos, ally3Pos)) {
                return TankRole.FLANKER;
            }

            // 3. 포위망 필요성: 적의 도주로 차단이 필요하면 INTERCEPTOR
            if (needsInterception(myPos, enemyPos, ally2Pos, ally3Pos)) {
                return TankRole.INTERCEPTOR;
            }

            // 4. 기본값: SUPPORTER
            return TankRole.SUPPORTER;
        }

        // 적에게 가장 가까운 탱크인지 확인
        static boolean isClosestToEnemy(int[] myPos, int[] ally2Pos, int[] ally3Pos, int[] enemyPos) {
            int myDist = TeamSituationAnalyzer.getDistance(myPos, enemyPos);
            int ally2Dist = TeamSituationAnalyzer.getDistance(ally2Pos, enemyPos);
            int ally3Dist = TeamSituationAnalyzer.getDistance(ally3Pos, enemyPos);

            return myDist <= ally2Dist && myDist <= ally3Dist;
        }

        // 측면 공격 가능한지 확인 (적과 교전 중인 아군과 90도 이상 각도 차이)
        static boolean canFlank(int[] myPos, int[] enemyPos, int[] ally2Pos, int[] ally3Pos) {
            double myAngle = TeamSituationAnalyzer.getAngleToEnemy(myPos, enemyPos);
            double ally2Angle = TeamSituationAnalyzer.getAngleToEnemy(ally2Pos, enemyPos);
            double ally3Angle = TeamSituationAnalyzer.getAngleToEnemy(ally3Pos, enemyPos);

            // 교전 중인 아군과 90도 이상 차이나면 측면 공격 가능
            boolean canFlankAlly2 = TeamSituationAnalyzer.isAllyEngaging(ally2Pos, enemyPos)
                                   && Math.abs(angleDifference(myAngle, ally2Angle)) >= 90;
            boolean canFlankAlly3 = TeamSituationAnalyzer.isAllyEngaging(ally3Pos, enemyPos)
                                   && Math.abs(angleDifference(myAngle, ally3Angle)) >= 90;

            return canFlankAlly2 || canFlankAlly3;
        }

        // 차단이 필요한 상황인지 확인
        static boolean needsInterception(int[] myPos, int[] enemyPos, int[] ally2Pos, int[] ally3Pos) {
            // 적이 퇴각하려는 상황 (아군이 2명 이상 적과 가까이 있을 때)
            int closeAllies = 0;
            if (TeamSituationAnalyzer.getDistance(ally2Pos, enemyPos) <= 5) closeAllies++;
            if (TeamSituationAnalyzer.getDistance(ally3Pos, enemyPos) <= 5) closeAllies++;

            return closeAllies >= 1 && TeamSituationAnalyzer.getDistance(myPos, enemyPos) > 5;
        }

        // 각도 차이 계산 (-180 ~ 180)
        static double angleDifference(double angle1, double angle2) {
            double diff = angle1 - angle2;
            while (diff > 180) diff -= 360;
            while (diff < -180) diff += 360;
            return diff;
        }
    }

    // 포위망 형성 시스템
    private static class EncirclementFormation {
        static final int OPTIMAL_ATTACK_DISTANCE = 4; // 안전한 공격 거리
        static final double[] PREFERRED_ANGLES = {0, 120, 240}; // 120도씩 분산

        // 역할에 따른 최적 위치 계산
        static int[] calculateOptimalPosition(String[][] grid, TankRole myRole) {
            int[] enemyPos = findNearestEnemyToMe(grid, TeamSituationAnalyzer.getMyPosition(grid));
            if (enemyPos == null) return TeamSituationAnalyzer.getMyPosition(grid);

            switch(myRole) {
                case AGGRESSOR:
                    return calculateFrontalPosition(grid, enemyPos);
                case FLANKER:
                    return calculateFlankingPosition(grid, enemyPos);
                case INTERCEPTOR:
                    return calculateInterceptionPosition(grid, enemyPos);
                case SUPPORTER:
                    return calculateSupportPosition(grid, enemyPos);
                case SUPPLIER:
                    return calculateSupplyPosition(grid);
                default:
                    return TeamSituationAnalyzer.getMyPosition(grid);
            }
        }

        // 정면 압박 위치 (적과 직선 거리 유지하며 접근)
        static int[] calculateFrontalPosition(String[][] grid, int[] enemyPos) {
            int[] myPos = TeamSituationAnalyzer.getMyPosition(grid);

            // 적 방향으로 OPTIMAL_ATTACK_DISTANCE만큼 떨어진 위치 계산
            int dr = enemyPos[0] - myPos[0];
            int dc = enemyPos[1] - myPos[1];

            // 정규화
            int dist = Math.abs(dr) + Math.abs(dc);
            if (dist == 0) return myPos;

            double ratio = (double) OPTIMAL_ATTACK_DISTANCE / dist;
            int targetR = enemyPos[0] - (int)(dr * ratio);
            int targetC = enemyPos[1] - (int)(dc * ratio);

            return clampToGrid(grid, targetR, targetC);
        }

        // 측면 공격 위치 (빈 각도 중 가장 유리한 위치)
        static int[] calculateFlankingPosition(String[][] grid, int[] enemyPos) {
            double[] occupiedAngles = TeamSituationAnalyzer.getTeamAnglesAroundEnemy(grid);
            double optimalAngle = findBestFlankingAngle(occupiedAngles);

            return calculatePositionAtAngle(grid, enemyPos, optimalAngle, OPTIMAL_ATTACK_DISTANCE);
        }

        // 차단 위치 (적의 예상 도주 방향)
        static int[] calculateInterceptionPosition(String[][] grid, int[] enemyPos) {
            int[] escapeRoute = predictEnemyEscapeRoute(grid, enemyPos);
            return escapeRoute != null ? escapeRoute : calculateSupportPosition(grid, enemyPos);
        }

        // 지원 위치 (교전 중인 아군 지원 또는 H 보호)
        static int[] calculateSupportPosition(String[][] grid, int[] enemyPos) {
            int[] ally2Pos = TeamSituationAnalyzer.getAllyPosition(grid, "M2");
            int[] ally3Pos = TeamSituationAnalyzer.getAllyPosition(grid, "M3");

            // 교전 중인 아군이 있으면 그 아군 지원
            if (TeamSituationAnalyzer.isAllyEngaging(ally2Pos, enemyPos)) {
                return calculateSupportPositionFor(grid, ally2Pos, enemyPos);
            }
            if (TeamSituationAnalyzer.isAllyEngaging(ally3Pos, enemyPos)) {
                return calculateSupportPositionFor(grid, ally3Pos, enemyPos);
            }

            // 그렇지 않으면 H 포탑 보호
            int[] hPos = findAllyTurret(grid);
            if (hPos != null) {
                return calculateDefensePosition(grid, hPos, enemyPos);
            }

            return TeamSituationAnalyzer.getMyPosition(grid);
        }

        // 특정 아군을 지원하는 위치
        static int[] calculateSupportPositionFor(String[][] grid, int[] allyPos, int[] enemyPos) {
            // 아군과 적 사이의 중점에서 약간 측면으로
            int midR = (allyPos[0] + enemyPos[0]) / 2;
            int midC = (allyPos[1] + enemyPos[1]) / 2;

            // 측면으로 1-2칸 이동
            int offsetR = (enemyPos[1] - allyPos[1] > 0) ? 1 : -1;
            int offsetC = (enemyPos[0] - allyPos[0] < 0) ? 1 : -1;

            return clampToGrid(grid, midR + offsetR, midC + offsetC);
        }

        // H 포탑 방어 위치
        static int[] calculateDefensePosition(String[][] grid, int[] hPos, int[] enemyPos) {
            // H와 적 사이에 위치
            int r = (hPos[0] + enemyPos[0]) / 2;
            int c = (hPos[1] + enemyPos[1]) / 2;
            return clampToGrid(grid, r, c);
        }

        // 보급 위치 계산 (가장 가까운 F의 인접 타일)
        static int[] calculateSupplyPosition(String[][] grid) {
            int[] myPos = TeamSituationAnalyzer.getMyPosition(grid);
            if (myPos == null) return null;

            int[][] dirs = {{0,1}, {1,0}, {0,-1}, {-1,0}};
            int minDist = Integer.MAX_VALUE;
            int[] bestSupplyPos = null;

            // 모든 F 위치 탐색
            for (int r = 0; r < grid.length; r++) {
                for (int c = 0; c < grid[0].length; c++) {
                    if ("F".equals(grid[r][c])) {
                        // F의 인접 타일 중 이동 가능한 곳 찾기
                        for (int[] dir : dirs) {
                            int ar = r + dir[0];
                            int ac = c + dir[1];
                            if (ar >= 0 && ar < grid.length && ac >= 0 && ac < grid[0].length) {
                                String cell = grid[ar][ac];
                                if (cell == null || cell.equals("G") || cell.equals("S")) {
                                    int dist = Math.abs(myPos[0] - ar) + Math.abs(myPos[1] - ac);
                                    if (dist < minDist) {
                                        minDist = dist;
                                        bestSupplyPos = new int[]{ar, ac};
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return bestSupplyPos != null ? bestSupplyPos : myPos;
        }

        // 각도와 거리로 위치 계산
        static int[] calculatePositionAtAngle(String[][] grid, int[] center, double angle, int distance) {
            double radians = Math.toRadians(angle);
            int r = center[0] + (int)(distance * Math.sin(radians));
            int c = center[1] + (int)(distance * Math.cos(radians));
            return clampToGrid(grid, r, c);
        }

        // 최적 측면 공격 각도 찾기
        static double findBestFlankingAngle(double[] occupiedAngles) {
            // 기본적으로 90도 (측면)를 선호
            double[] candidateAngles = {90, 270, 180, 0};

            for (double candidate : candidateAngles) {
                boolean tooClose = false;
                for (double occupied : occupiedAngles) {
                    if (occupied != -1 && Math.abs(DynamicRoleAssigner.angleDifference(candidate, occupied)) < 60) {
                        tooClose = true;
                        break;
                    }
                }
                if (!tooClose) return candidate;
            }

            return 90; // 기본값
        }

        // 적의 도주 경로 예측
        static int[] predictEnemyEscapeRoute(String[][] grid, int[] enemyPos) {
            int[] ally2Pos = TeamSituationAnalyzer.getAllyPosition(grid, "M2");
            int[] ally3Pos = TeamSituationAnalyzer.getAllyPosition(grid, "M3");

            // 가장 가까운 아군 반대 방향으로 도주할 것으로 예상
            int[] closestAlly = null;
            int minDist = Integer.MAX_VALUE;

            if (ally2Pos != null) {
                int dist = TeamSituationAnalyzer.getDistance(ally2Pos, enemyPos);
                if (dist < minDist) {
                    minDist = dist;
                    closestAlly = ally2Pos;
                }
            }

            if (ally3Pos != null) {
                int dist = TeamSituationAnalyzer.getDistance(ally3Pos, enemyPos);
                if (dist < minDist) {
                    closestAlly = ally3Pos;
                }
            }

            if (closestAlly != null) {
                // 가장 가까운 아군 반대 방향으로 3칸 떨어진 위치
                int dr = enemyPos[0] - closestAlly[0];
                int dc = enemyPos[1] - closestAlly[1];
                int escapeR = enemyPos[0] + (dr > 0 ? 3 : dr < 0 ? -3 : 0);
                int escapeC = enemyPos[1] + (dc > 0 ? 3 : dc < 0 ? -3 : 0);
                return clampToGrid(grid, escapeR, escapeC);
            }

            return null;
        }

        // 그리드 범위 내로 좌표 제한
        static int[] clampToGrid(String[][] grid, int r, int c) {
            int rows = grid.length;
            int cols = grid[0].length;
            r = Math.max(0, Math.min(rows - 1, r));
            c = Math.max(0, Math.min(cols - 1, c));
            return new int[]{r, c};
        }
    }

    // 역할별 행동 로직
    private static class RoleBasedBehavior {
        // 팀 협동 이동 메인 함수
        static String chooseTeamCoordinatedMove(String[][] grid, int[] myPos, int[][] dirs, String[] moveCmds, boolean[][] threat) {
            // 1. 역할 결정
            TankRole myRole = DynamicRoleAssigner.determineMyRole(grid);

            // 2. 역할에 맞는 목표 위치 계산
            int[] targetPos = EncirclementFormation.calculateOptimalPosition(grid, myRole);

            // 2-1. 아군 집결 대기: 아군 중심과의 집결 링(2~3)에 접근, 동료가 2칸 내 들어오면 교전 수행
            int[] allyCent = TeamSituationAnalyzer.getAllyPosition(grid, "M2");
            int[] ally3 = TeamSituationAnalyzer.getAllyPosition(grid, "M3");
            int closeAllies = 0;
            if (allyCent != null && TeamSituationAnalyzer.getDistance(myPos, allyCent) <= 2) closeAllies++;
            if (ally3 != null && TeamSituationAnalyzer.getDistance(myPos, ally3) <= 2) closeAllies++;
            if (closeAllies < 1) {
                boolean[][] rvGoals = buildRendezvousGoals(grid, computeAllyCentroidForT1(grid),  null);
                if (rvGoals != null) {
                    int[] hPos = findAllyTurret(grid);
                    Queue<String> path = aStarToGoals(grid, myPos, rvGoals, dirs, moveCmds, threat, hPos);
                    if (path != null && !path.isEmpty()) {
                        String towardRv = path.poll();
                        System.out.printf("[T1-TEAM] wait-for-allies action=%s\n", towardRv);
                        return towardRv;
                    }
                }
            }

            // 3. 포위망 완성 여부 확인
            if (EncirclementDetector.shouldExecuteConcentratedAttack(grid)) {
                return executeConcentratedAttack(grid, myPos, dirs, moveCmds, threat, myRole);
            }

            // 4. 역할별 행동 실행
            String action = executeRoleBasedMove(grid, myPos, targetPos, dirs, moveCmds, threat, myRole);

            // 5. 디버그 출력
            System.out.printf("[TEAM] role=%s target=(%d,%d) action=%s\n",
                              myRole, targetPos[0], targetPos[1], action);

            return action;
        }

        // 집결 유틸 (간단 버전): 아군 중심 기반 링(2~3)
        static int[] computeAllyCentroidForT1(String[][] grid){ int sr=0, sc=0, cnt=0; for(int r=0;r<grid.length;r++){ for(int c=0;c<grid[0].length;c++){ String cell=grid[r][c]; if(cell==null) continue; if(cell.equals("M")||cell.startsWith("M")){ sr+=r; sc+=c; cnt++; } } } if(cnt==0) return null; return new int[]{sr/cnt, sc/cnt}; }
        static boolean[][] buildRendezvousGoals(String[][] grid, int[] allyCent, int[] enemyPos){ if(allyCent==null) return null; int rows=grid.length, cols=grid[0].length; boolean[][] goals=new boolean[rows][cols]; boolean any=false; for(int r=allyCent[0]-3;r<=allyCent[0]+3;r++){ for(int c=allyCent[1]-3;c<=allyCent[1]+3;c++){ if(r<0||r>=rows||c<0||c>=cols) continue; int md=Math.abs(r-allyCent[0])+Math.abs(c-allyCent[1]); if(md<2||md>3) continue; String cell=grid[r][c]; if(cell==null) continue; if(!isWalkable(cell)) continue; if(isBlockedByAllyTurretZone(grid,r,c)) continue; goals[r][c]=true; any=true; } } return any?goals:null; }

        // 역할별 이동 실행
        static String executeRoleBasedMove(String[][] grid, int[] myPos, int[] targetPos,
                                         int[][] dirs, String[] moveCmds, boolean[][] threat, TankRole role) {
            int rows = grid.length, cols = grid[0].length;
            int[] enemyPos = findNearestEnemyToMe(grid, myPos);
            if (enemyPos != null && shouldEngageNow(grid, myPos)){
                // 턴-거리 기반 선택: 내 선공까지 최소 이동턴 vs 적 반격턴 마진을 최대화
                int bestDir=-1; int bestMyTurns=Integer.MAX_VALUE; int bestMargin=Integer.MIN_VALUE; int bestEnemyDist=Integer.MAX_VALUE;
                for(int d=0; d<4; d++){
                    int nr=myPos[0]+dirs[d][0], nc=myPos[1]+dirs[d][1];
                    if(nr<0||nr>=rows||nc<0||nc>=cols) continue; String cell=grid[nr][nc]; if(!isWalkable(cell)) continue;
                    if(isBlockedByAllyTurretZone(grid,nr,nc)) continue;
                    int[] next=new int[]{nr,nc};
                    int myTurns = minTurnsToShootTarget(grid, next, enemyPos, dirs, 12, false);
                    int enemyTurns = minTurnsToShootTarget(grid, enemyPos, next, dirs, 12, true);
                    int margin = (enemyTurns==Integer.MAX_VALUE? 50: enemyTurns) - (myTurns==Integer.MAX_VALUE? 50: myTurns);
                    int enemyDist = Math.abs(nr-enemyPos[0])+Math.abs(nc-enemyPos[1]);
                    // 우선순위: myTurns 최소 → margin 최대 → 적까지 거리 최소
                    if (myTurns < bestMyTurns || (myTurns==bestMyTurns && (margin>bestMargin || (margin==bestMargin && enemyDist<bestEnemyDist)))){
                        bestMyTurns = myTurns; bestMargin = margin; bestEnemyDist = enemyDist; bestDir = d;
                    }
                }
                return bestDir==-1? moveCmds[0] : moveCmds[bestDir];
            }

            // 적이 없을 때는 타겟 접근(보조)
            int bestDir = -1; int bestDist=Integer.MAX_VALUE;
            for (int d = 0; d < 4; d++) {
                int nr = myPos[0] + dirs[d][0];
                int nc = myPos[1] + dirs[d][1];
                if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) continue;
                String cell = grid[nr][nc]; if (!isWalkable(cell)) continue; if(isBlockedByAllyTurretZone(grid,nr,nc)) continue;
                int nd = TeamSituationAnalyzer.getDistance(new int[]{nr,nc}, targetPos);
                if (nd < bestDist){ bestDist=nd; bestDir=d; }
            }
            return bestDir==-1? moveCmds[0] : moveCmds[bestDir];
        }

        // 역할 기반 스코어 계산
        // 거리 기반 로직으로 대체되어 미사용(보존)

        // 역할별 스코어 조정
        static int applyRoleModifier(TankRole role, int[] nextPos, int[] enemyPos, int[][] dirs, String[][] grid) {
            switch (role) {
                case AGGRESSOR:
                    // 공격적: 적에게 가까워지는 것 선호, 위험 감수
                    if (enemyPos != null) {
                        int distToEnemy = TeamSituationAnalyzer.getDistance(nextPos, enemyPos);
                        if (distToEnemy <= 4) return +25; // 공격 거리 내 보너스
                        if (distToEnemy <= 6) return +15; // 근접 보너스
                    }
                    return 0;

                case FLANKER:
                    // 측면 공격: 은밀성 우선, 엄폐 선호
                    if (isAdjacentToCover(grid, nextPos, dirs)) return +20;
                    return +5; // 기본 측면 공격 보너스

                case INTERCEPTOR:
                    // 차단: 기동성 우선, 넓은 시야 확보
                    if (hasGoodVisibility(grid, nextPos)) return +15;
                    return +10; // 기본 차단 보너스

                case SUPPORTER:
                    // 지원: 안전성 우선, 아군과의 거리 고려
                    int[] hPos = findAllyTurret(grid);
                    if (hPos != null) {
                        int distToH = TeamSituationAnalyzer.getDistance(nextPos, hPos);
                        if (distToH <= 3) return +20; // H 근처 보너스
                    }
                    return +5; // 기본 지원 보너스

                default:
                    return 0;
            }
        }

        // 집중 공격 실행
        static String executeConcentratedAttack(String[][] grid, int[] myPos, int[][] dirs,
                                               String[] moveCmds, boolean[][] threat, TankRole role) {
            int[] enemyPos = findNearestEnemyToMe(grid, myPos);
            if (enemyPos == null) return moveCmds[0];

            // 포위가 완성되면 공격 우선
            LoSResult los = findFireLineOfSight(grid, myPos, dirs);
            if (los.canFire) {
                String[] fireCmds = {"R F", "D F", "L F", "U F"};
                return fireCmds[los.dirIndex];
            }

            // 공격 불가능하면 사격 각도 확보를 위해 이동
            return moveForAttackAngle(grid, myPos, enemyPos, dirs, moveCmds, threat);
        }

        // 공격 각도 확보를 위한 이동
        static String moveForAttackAngle(String[][] grid, int[] myPos, int[] enemyPos,
                                        int[][] dirs, String[] moveCmds, boolean[][] threat) {
            int bestScore = Integer.MIN_VALUE;
            int bestDir = -1;

            for (int d = 0; d < dirs.length; d++) {
                int nr = myPos[0] + dirs[d][0];
                int nc = myPos[1] + dirs[d][1];

                if (nr < 0 || nr >= grid.length || nc < 0 || nc >= grid[0].length) continue;
                if (!isWalkable(grid[nr][nc])) continue;

                int score = 0;

                // 사격 가능 여부 확인
                if (iCanFirstStrikeFrom(grid, new int[]{nr, nc}, dirs)) {
                    score += 60; // 사격 각도 확보 시 높은 점수
                }

                // 적과의 거리 고려 (4칸 거리 선호)
                int distToEnemy = TeamSituationAnalyzer.getDistance(new int[]{nr, nc}, enemyPos);
                if (distToEnemy == 4) score += 30;
                else if (distToEnemy == 3) score += 20;
                else if (distToEnemy > 6) score -= 20;

                // 위험 회피 (포위 완성 시에는 소폭 위험 감수)
                if (threat[nr][nc]) score -= 15;

                if (score > bestScore) {
                    bestScore = score;
                    bestDir = d;
                }
            }

            return bestDir == -1 ? moveCmds[0] : moveCmds[bestDir];
        }

        // 좋은 시야 확보 여부 (주변 빈 공간이 많은지)
        static boolean hasGoodVisibility(String[][] grid, int[] pos) {
            int clearTiles = 0;
            int[][] dirs = {{0,1}, {1,0}, {0,-1}, {-1,0}, {1,1}, {1,-1}, {-1,1}, {-1,-1}};

            for (int[] dir : dirs) {
                int nr = pos[0] + dir[0];
                int nc = pos[1] + dir[1];
                if (nr >= 0 && nr < grid.length && nc >= 0 && nc < grid[0].length) {
                    if (isWalkable(grid[nr][nc]) || "G".equals(grid[nr][nc])) {
                        clearTiles++;
                    }
                }
            }

            return clearTiles >= 5; // 8방향 중 5방향 이상 클리어
        }
    }

    // 포위망 완성 감지 시스템
    private static class EncirclementDetector {
        // 적이 포위되었는지 확인
        static boolean isEnemyEncircled(String[][] grid) {
            int[] enemyPos = findNearestEnemyToMe(grid, TeamSituationAnalyzer.getMyPosition(grid));
            if (enemyPos == null) return false;

            double[] teamAngles = TeamSituationAnalyzer.getTeamAnglesAroundEnemy(grid);
            return isProperlyEncircled(teamAngles);
        }

        // 적절한 포위망인지 확인 (120도 이상 간격으로 분산)
        static boolean isProperlyEncircled(double[] teamAngles) {
            if (teamAngles.length < 3) return false;

            // 유효한 각도만 추출 (-1이 아닌 값)
            java.util.List<Double> validAngles = new java.util.ArrayList<>();
            for (double angle : teamAngles) {
                if (angle != -1) validAngles.add(angle);
            }

            if (validAngles.size() < 2) return false;

            // 각도 정렬
            validAngles.sort(Double::compareTo);

            // 인접한 각도 간 최소 간격 확인
            for (int i = 0; i < validAngles.size(); i++) {
                double angle1 = validAngles.get(i);
                double angle2 = validAngles.get((i + 1) % validAngles.size());

                double diff = Math.abs(DynamicRoleAssigner.angleDifference(angle1, angle2));
                if (diff < 60) { // 60도 미만이면 너무 가까움
                    return false;
                }
            }

            // 유효한 각도가 2개 이상이면 기본 포위 조건 만족
            return validAngles.size() >= 2;
        }

        // 모든 아군이 위치에 있는지 확인
        static boolean allAlliesInPosition(String[][] grid) {
            int[] enemyPos = findNearestEnemyToMe(grid, TeamSituationAnalyzer.getMyPosition(grid));
            if (enemyPos == null) return false;

            // 각 아군이 적절한 공격 거리(3-5칸)에 있는지 확인
            int[] myPos = TeamSituationAnalyzer.getMyPosition(grid);
            int[] ally2Pos = TeamSituationAnalyzer.getAllyPosition(grid, "M2");
            int[] ally3Pos = TeamSituationAnalyzer.getAllyPosition(grid, "M3");

            int alliesInRange = 0;
            if (isInAttackRange(myPos, enemyPos)) alliesInRange++;
            if (isInAttackRange(ally2Pos, enemyPos)) alliesInRange++;
            if (isInAttackRange(ally3Pos, enemyPos)) alliesInRange++;

            return alliesInRange >= 2;
        }

        // 공격 범위에 있는지 확인 (3-5칸 거리)
        static boolean isInAttackRange(int[] pos, int[] enemyPos) {
            if (pos == null || enemyPos == null) return false;
            int dist = TeamSituationAnalyzer.getDistance(pos, enemyPos);
            return dist >= 3 && dist <= 5;
        }

        // 집중 공격 신호 (모든 조건 만족 시)
        static boolean shouldExecuteConcentratedAttack(String[][] grid) {
            return isEnemyEncircled(grid) && allAlliesInPosition(grid);
        }
    }

    // 팀 협동 이동 메인 함수 (기존 choosePatrolMove 대체)
    private static String chooseTeamCoordinatedMove(String[][] grid, int[] myPos, int[][] dirs, String[] moveCmds, boolean[][] threat) {
        return RoleBasedBehavior.chooseTeamCoordinatedMove(grid, myPos, dirs, moveCmds, threat);
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
        if (myAllies.containsKey("H")) return "H";
        if (myAllies.containsKey("X")) return "X";
        return "H"; // 기본값
    }

    // 아군 포탑 배제 구역(H 중심 -2..+2 정사각형) 진입 차단
    private static boolean isBlockedByAllyTurretZone(String[][] grid, int r, int c) {
        int[] h = findAllyTurret(grid);
        if (h == null) return false;
        return Math.abs(r - h[0]) <= 2 && Math.abs(c - h[1]) <= 2;
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

    // 국지적 체력 우위: 반경 radius 내 아군 HP 합이 적 HP 합보다 충분히 큼
    private static boolean hasLocalHpSuperiority(String[][] grid, int[] center, int radius){
        int rows=grid.length, cols=grid[0].length; int allyHp=0, enemyHp=0;
        for(int r=center[0]-radius; r<=center[0]+radius; r++){
            for(int c=center[1]-radius; c<=center[1]+radius; c++){
                if(r<0||r>=rows||c<0||c>=cols) continue; String cell=grid[r][c]; if(cell==null) continue;
                if(cell.equals("M")||cell.startsWith("M")) allyHp += getAllyHp(cell);
                else if(cell.equals("X")||cell.startsWith("E")) enemyHp += getEnemyHp(cell);
            }
        }
        return allyHp >= enemyHp + 20; // 여유 마진
    }

    private static int getAllyHp(String symbol){ String[] info = myAllies.get(symbol); if(info==null) return 0; return parseIntSafe(info[0]); }
    private static int getEnemyHp(String symbol){ String[] info = enemies.get(symbol); if(info==null) return 0; return parseIntSafe(info[0]); }

    // 전진선 맞추기: 적에 더 가까워지는 단순 전진
    private static String chooseAggressiveAdvanceMove(String[][] grid, int[] myPos, int[][] dirs, String[] moveCmds){
        int[] enemy = findNearestEnemyToMe(grid, myPos); if(enemy==null) return null;
        int best=-1, bestScore=Integer.MIN_VALUE; for(int d=0; d<4; d++){
            int nr=myPos[0]+dirs[d][0], nc=myPos[1]+dirs[d][1];
            if(nr<0||nr>=grid.length||nc<0||nc>=grid[0].length) continue; String cell=grid[nr][nc]; if(!isWalkable(cell)) continue; if(isBlockedByAllyTurretZone(grid,nr,nc)) continue;
            int curDist=Math.abs(myPos[0]-enemy[0])+Math.abs(myPos[1]-enemy[1]);
            int nextDist=Math.abs(nr-enemy[0])+Math.abs(nc-enemy[1]);
            int score=0; if(nextDist<curDist) score+=30; if("S".equals(cell)) score-=5;
            if(score>bestScore){ bestScore=score; best=d; }
        }
        return best==-1? null : moveCmds[best];
    }

    // 집결 실패 시 랜덤 전진: 턴 기반 의사난수로 4방 시도, 보행/차단 검사
    private static String chooseRandomAdvanceMove(String[][] grid, int[] myPos, int[][] dirs, String[] moveCmds, int turn){
        int seed = (turn*1103515245 + 12345);
        int[] order = new int[]{seed&3, (seed>>2)&3, (seed>>4)&3, (seed>>6)&3};
        boolean[] used = new boolean[4]; int chosen=-1;
        for(int k=0;k<4;k++){
            int d = order[k]; if(used[d]) continue; used[d]=true;
            int nr=myPos[0]+dirs[d][0], nc=myPos[1]+dirs[d][1];
            if(nr<0||nr>=grid.length||nc<0||nc>=grid[0].length) continue; String cell=grid[nr][nc];
            if(!isWalkable(cell)) continue; if(isBlockedByAllyTurretZone(grid,nr,nc)) continue;
            chosen=d; break;
        }
        return chosen==-1? moveCmds[0] : moveCmds[chosen];
    }

    // 보급 재경로: 위협 회피 + 아군 중심 쪽 F 인접칸으로 우회
    private static Queue<String> rerouteToAllySideSupply(String[][] grid, int[] start, int[][] dirs, String[] moveCmds, boolean[][] threat){
        int rows=grid.length, cols=grid[0].length; int[] allyCent = RoleBasedBehavior.computeAllyCentroidForT1(grid);
        boolean[][] goals=new boolean[rows][cols]; boolean any=false;
        for(int r=0;r<rows;r++){
            for(int c=0;c<cols;c++){
                if(!"F".equals(grid[r][c])) continue;
                for(int d=0; d<4; d++){
                    int nr=r+dirs[d][0], nc=c+dirs[d][1]; if(nr<0||nr>=rows||nc<0||nc>=cols) continue;
                    String cell=grid[nr][nc]; if(cell==null) continue; if(!isWalkable(cell)) continue; if(isBlockedByAllyTurretZone(grid,nr,nc)) continue;
                    if(threat!=null && threat[nr][nc]) continue;
                    if(allyCent==null || (Math.abs(nr-allyCent[0])+Math.abs(nc-allyCent[1]) <= Math.max(rows,cols))){ goals[nr][nc]=true; any=true; }
                }
            }
        }
        if(!any) return null; int[] hPos = findAllyTurret(grid);
        return aStarToGoals(grid, start, goals, dirs, moveCmds, threat, hPos);
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
            if(!isWalkable(cell)) continue;
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
    	// Tank1은 수비 이탈 가중치 제거 (Tank2 전담)
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

    // ===== 보급 응급 상황 감지 시스템 =====
    private static final int CRITICAL_SHELL_THRESHOLD = 5; // 일반탄 임계값
    private static final int CRITICAL_MEGA_THRESHOLD = 0; // 메가탄 임계값 (0개면 긴급)
    private static final int STALEMATE_THRESHOLD = 3; // 교착 감지 턴수
    private static final int EMERGENCY_TEAM_MEGA_MIN = 2; // 팀 전체 메가탄 위기 임계값
    private static final int MEGA_TARGET_PER_TANK = 2; // 최소 보급 수량: 탱크당 2발 확보
    private static final int TEAM_MEGA_MIN_RESERVE = 3; // 팀 최소 보급 총량

    // 탄약 부족 위험 레벨 감지
    private static int getAmmoRiskLevel(int myShell, int myMega, int teamMegaTotal) {
        // Level 3 (위기): 일반탄 부족 OR 메가탄 0개 OR 팀 전체 메가탄 부족
        if (myShell <= CRITICAL_SHELL_THRESHOLD || myMega <= CRITICAL_MEGA_THRESHOLD || teamMegaTotal <= EMERGENCY_TEAM_MEGA_MIN) {
            return 3; // 위기
        }
        // Level 2 (경고): 개별 탱크 메가탄 목표치 미달
        if (myMega < MEGA_TARGET_PER_TANK) {
            return 2; // 경고
        }
        // Level 1 (정상): 충분한 탄약 보유
        return 1; // 정상
    }

    // 교착 상황 감지 및 업데이트
    private static boolean updateStalemate(int[] currentPos, int turn) {
        // 위치 기록 업데이트 (최근 3턴)
        System.arraycopy(positionHistory, 2, positionHistory, 0, 4);
        positionHistory[4] = currentPos[0];
        positionHistory[5] = currentPos[1];

        // 교착 상황 판단: 최근 3턴이 모두 기록되었고, 반경 1칸 내에서만 움직임
        if (turn >= 3) {
            int r1 = positionHistory[0], c1 = positionHistory[1]; // 3턴 전
            int r2 = positionHistory[2], c2 = positionHistory[3]; // 2턴 전
            int r3 = positionHistory[4], c3 = positionHistory[5]; // 현재

            int maxDist = Math.max(Math.max(Math.abs(r1-r2) + Math.abs(c1-c2),
                                           Math.abs(r2-r3) + Math.abs(c2-c3)),
                                   Math.abs(r1-r3) + Math.abs(c1-c3));

            if (maxDist <= 2) { // 반경 2칸 내에서만 움직임
                stuckTurnCount++;
                if (stuckTurnCount >= STALEMATE_THRESHOLD) {
                    return true; // 교착 상황 감지
                }
            } else {
                stuckTurnCount = 0; // 교착 해제
            }
        }
        return false;
    }

    // 보급 응급 모드 판단
    private static boolean shouldEnterEmergencySupplyMode(int myShell, int myMega, int teamMegaTotal,
                                                          boolean isStalemate, int turn, boolean codesAvailable) {
        // MAX 2발 이상이면 응급 모드 진입 불가
        if (myMega >= MEGA_TARGET_PER_TANK) return false;
        int riskLevel = getAmmoRiskLevel(myShell, myMega, teamMegaTotal);

        // Level 3 위기: 즉시 응급 보급 모드
        if (riskLevel >= 3 && codesAvailable) {
            return true;
        }

        // Level 2 경고: 메가탄 부족이면 즉시 보급 시도 (교착 조건 완화)
        if (riskLevel >= 2 && codesAvailable && (turn - lastSupplyTurn) > 3) {
            return true;
        }

        // 초반 8턴 이내에 메가탄 0개면 강제 보급
        if (turn < 8 && myMega == 0 && codesAvailable) {
            return true;
        }

        return false;
    }

    // 보급 최종 우선순위 결정 (기존 로직 오버라이드)
    private static boolean calculateSupplyPriority(int myShell, int myMega, int teamMegaTotal, boolean isStalemate,
                                                   int turn, boolean codesAvailable, boolean myTurnForSupply,
                                                   boolean earlyPhase, int distToF, int distToEnemy, boolean approaching, boolean inThreat) {
        // MAX 2발 도달 시 보급 중단
        if (myMega >= MEGA_TARGET_PER_TANK) {
            emergencySupplyMode = false;
            return false;
        }

        // 1. 응급 모드 확인
        if (shouldEnterEmergencySupplyMode(myShell, myMega, teamMegaTotal, isStalemate, turn, codesAvailable)) {
            emergencySupplyMode = true;
            return true;
        }

        // 2. 기존 로직 (초반)
        if (earlyPhase && myMega < MEGA_TARGET_PER_TANK && codesAvailable) {
            return true;
        }

        // 3. 기존 로직 (일반)
        boolean needMega = (myMega < MEGA_TARGET_PER_TANK) && (teamMegaTotal < TEAM_MEGA_MIN_RESERVE || earlyPhase) && (myMega < 10);
        boolean supplyPreferred = needMega && codesAvailable && (distToF >= 0) && (distToEnemy < 0 || distToF < distToEnemy);

        // 근접 링 제어 완화: 응급 상황에서는 라운드로빈 무시
        if (!earlyPhase && distToF >= 0 && distToF <= 2 && !myTurnForSupply && !emergencySupplyMode) {
            supplyPreferred = false;
        }

        return supplyPreferred || (needMega && codesAvailable && myTurnForSupply && !(enemyApproachStreak >= 2) && !inThreat);
    }

    // ===== 방어 위기 판단 및 수비라인 구성 =====
    private static boolean isDefenseEmergency(String[][] grid){
        // 기존 수비모드 판단 + 접근 추세 플래그로 강화
        return isDefenseMode(grid) && defenseProactiveFlag;
    }

    private static java.util.List<int[]> enumerateDefenseRing(String[][] grid, int radius){
        java.util.List<int[]> list = new java.util.ArrayList<>();
        int[] h = findAllyTurret(grid); if(h==null) return list;
        int rows=grid.length, cols=grid[0].length;
        for(int r=h[0]-radius; r<=h[0]+radius; r++){
            for(int c=h[1]-radius; c<=h[1]+radius; c++){
                if(r<0||r>=rows||c<0||c>=cols) continue;
                int md=Math.abs(r-h[0])+Math.abs(c-h[1]); if(md!=radius) continue;
                String cell=grid[r][c]; if(cell==null) continue; if(!isWalkable(cell)) continue;
                if(isBlockedByAllyTurretZone(grid,r,c)) continue; // -2..+2 금지
                list.add(new int[]{r,c});
            }
        }
        return list;
    }

    private static int scoreDefenseCell(String[][] grid, int[] cell, int[] enemyFocus){
        // 점수: 적 중심과의 근접(+), 사거리 3 내에서 적 중심을 조준 가능(+), 모래 소폭(-)
        int score = 0;
        if(enemyFocus!=null){
            int d = Math.abs(cell[0]-enemyFocus[0]) + Math.abs(cell[1]-enemyFocus[1]);
            score += Math.max(0, 15 - d);
            if (canShootTargetFrom(grid, cell, enemyFocus, new int[][]{{0,1},{1,0},{0,-1},{-1,0}})) score += 20;
        }
        if("S".equals(grid[cell[0]][cell[1]])) score -= 2;
        return score;
    }

    private static int[] assignDefenseGoalForSelf(String[][] grid){
        int[] h = findAllyTurret(grid); if(h==null) return null;
        int[] enemyFocus = findNearestEnemyToH(grid);
        java.util.List<int[]> ring = enumerateDefenseRing(grid, 3);
        if (ring.isEmpty()) return null;
        // 점수로 정렬
        ring.sort((a,b)-> Integer.compare(
            scoreDefenseCell(grid,b,enemyFocus),
            scoreDefenseCell(grid,a,enemyFocus))
        );
        // 클라이언트 인덱스로 슬롯 배정(겹침 최소화)
        int idx = getClientIndex() % 3; if (idx<0) idx=0;
        // 후보 슬롯이 3개 미만이면 가용 범위 내에서 선택
        if (idx >= ring.size()) idx = ring.size()-1;
        int[] goal = ring.get(idx);
        // 만약 목표가 즉시 사용 불가면 순차적으로 다음 가용 슬롯 선택
        for(int i=0;i<ring.size();i++){
            int at = (idx + i) % ring.size();
            int[] g = ring.get(at);
            if (!isBlockedByAllyTurretZone(grid,g[0],g[1])) return g;
        }
        return goal;
    }

    private static String chooseDefenseLineMove(String[][] grid, int[] myPos, int[][] dirs, String[] moveCmds, boolean[][] threat){
        int[] goal = assignDefenseGoalForSelf(grid);
        if (goal == null) return choosePatrolMove(grid, myPos, dirs, moveCmds, threat);
        boolean[][] goals = new boolean[grid.length][grid[0].length];
        goals[goal[0]][goal[1]] = true;
        int[] hPos = findAllyTurret(grid);
        Queue<String> path = aStarToGoals(grid, myPos, goals, dirs, moveCmds, threat, hPos);
        if (path!=null && !path.isEmpty()) return path.poll();
        return choosePatrolMove(grid, myPos, dirs, moveCmds, threat);
    }

    // 수비 중 보급 허용 판단(정지 해독): 적-포탑 최소거리 여유가 충분할 때 허용
    private static boolean canResupplyWhileDefending(String[][] grid){
        int md = getMinDistEnemyToH(grid); // 적과 H의 최소 맨해튼 거리
        return md >= 6; // 여유 임계값(6칸 이상)
    }

    // 수비 중 보급 인접 이동 허용(이동 보급): 여유 더 클 때만 허용
    private static boolean canMoveToSupplyWhileDefending(String[][] grid){
        int md = getMinDistEnemyToH(grid);
        return md >= 7; // 이동 포함 여유 임계값
    }

    // ===== 축 기반 포위 포지셔닝(T1) =====
    private static boolean isInside(String[][] grid,int r,int c){ return r>=0 && r<grid.length && c>=0 && c<grid[0].length; }
    private static int[] preferredAxisOrder(){ int idx=getClientIndex()%3; if(idx==0) return new int[]{3,2,0,1}; if(idx==1) return new int[]{2,0,3,1}; return new int[]{0,3,2,1}; }
    private static boolean enemyNearby(String[][] grid, int[] myPos){ int d=distanceToNearestEnemy(grid, myPos); return d>=0 && d<=6; }
    private static boolean[][] buildAxisGoals(String[][] grid, int[] enemyPos, int[][] dirs, boolean[][] threat){ boolean[][] goals=new boolean[grid.length][grid[0].length]; int[] ord=preferredAxisOrder(); boolean any=false; for(int oi=0; oi<ord.length; oi++){ int d=ord[oi]; for(int dist=3; dist>=2; dist--){ int gr=enemyPos[0]+dirs[d][0]*dist, gc=enemyPos[1]+dirs[d][1]*dist; if(!isInside(grid,gr,gc)) continue; String cell=grid[gr][gc]; if(cell==null) continue; if(!isWalkable(cell)) continue; if(isBlockedByAllyTurretZone(grid,gr,gc)) continue; if(threat!=null && threat[gr][gc]) continue; goals[gr][gc]=true; any=true; break; } } return any?goals:null; }
    private static String moveToAxisEncirclement(String[][] grid, int[] myPos, int[][] dirs, String[] moveCmds, boolean[][] threat){ int[] enemy=findNearestEnemyToMe(grid,myPos); if(enemy==null) return null; boolean[][] goals=buildAxisGoals(grid, enemy, dirs, threat); if(goals==null) return null; int[] hPos=findAllyTurret(grid); Queue<String> path=aStarToGoals(grid, myPos, goals, dirs, moveCmds, threat, hPos); if(path==null||path.isEmpty()) return null; return path.poll(); }

}
