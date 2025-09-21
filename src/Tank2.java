import java.net.*;
import java.io.*;
import java.util.*;

public class Tank2 {
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
    // 선제 수비 모드 파라미터/상태
    private static final int PROACTIVE_DEF_DIST = 5;
    private static int prevMinDistToH = Integer.MAX_VALUE;
    private static int approachTrendCount = 0;
    private static boolean defenseProactiveFlag = false;
    // 교착/교전 추적 상태
    private static int lastR = -1, lastC = -1; // 직전 좌표
    private static int lastMoveDir = -1; // 0:R,1:D,2:L,3:U
    private static boolean lastWasMove = false; // 직전 명령이 이동인지
    private static boolean recentStuck = false; // 직전 턴 이동 실패(좌표 동일)
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
        String NICKNAME = "통제자";
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
        // 포탑 방어 파라미터
        int MEGA_TARGET_PER_TANK = 2; // 최소 보급 수량: 탱크당 2발 확보
        int TEAM_MEGA_MIN_RESERVE = 3; // 팀 최소 보급 총량
        int EARLY_SUPPLY_TURNS = 8; // 초반 보급 우선 턴수
        // 보급 응급 임계값
        final int STALEMATE_THRESHOLD = 3; // 교착 감지 턴수
        int turn = 0;

        // 최초 데이터 파싱
        parseData(gameData);
        
        // 출발지점, 목표지점의 위치 확인
        int[][] positions = findPositions(mapData, START_SYMBOL, TARGET_SYMBOL);
        int[] start = positions[0];
        int[] target = positions[1];
        if (start == null || target == null) {
            close();
            return;
        }

        // 반복문: 메인 프로그램 <-> 클라이언트(이 코드) 간 순차로 데이터 송수신(동기 처리)
        while (gameData != null && gameData.length() > 0) {
        	
            // 디버그 출력 제거

            // 내 위치/방향/탄약 파악
            int[] myPos = findPositions(mapData, START_SYMBOL, TARGET_SYMBOL)[0];
            String[] me = myAllies.get("M");
            int myShell = me != null && me.length >= 3 ? parseIntSafe(me[2]) : 0; // 일반 포탄
            int myMega = me != null && me.length >= 4 ? parseIntSafe(me[3]) : 0;
            String debugMode = "";
            int distToF = -1;
            int distToEnemy = -1;

            // 선제 수비 모드 추세 갱신
            int curMinDistToH = getMinDistEnemyToH(mapData);
            if (curMinDistToH < prevMinDistToH) approachTrendCount++; else approachTrendCount = 0;
            prevMinDistToH = curMinDistToH;
            defenseProactiveFlag = (curMinDistToH > -1 && curMinDistToH <= PROACTIVE_DEF_DIST) || (approachTrendCount >= 2);

            // 1) 사격 우선
            // [PREEMPT] 극초반: 메가 < 2면 사격보다 보급을 우선
            String output;
            int curNearestEnemyDist = lastNearestEnemyDist;
            if (turn < EARLY_SUPPLY_TURNS && myMega < MEGA_TARGET_PER_TANK) {
                if (isAdjacentToSupply(mapData, myPos, DIRS)) {
                    boolean earlyPhase = true;
                    boolean needMega = true;
                    boolean codesAvailable = (codes != null && codes.length > 0);
                    if (codesAvailable) {
                        String cipher = codes[0].trim(); String plain = decodeCaesarShift(cipher, 9); output = "G " + plain;
                        debugMode = "SUPPLY_DECODE_EARLY";
                    } else {
                        // 인접인데 코드가 없으면 다른 F로 재경로, 없으면 직진 한 스텝
                        boolean[][] threatEarly = buildThreatMap(mapData, DIRS);
                        Queue<String> reroute = rerouteToAllySideSupply(mapData, myPos, DIRS, MOVE_CMDS, threatEarly);
                        if (reroute != null && !reroute.isEmpty()) { output = reroute.poll(); debugMode = "REROUTE_SAFE_SUPPLY_EARLY"; }
                        else { output = greedyStepToNearestSupplyAdj(mapData, myPos, DIRS, MOVE_CMDS); debugMode = "GREEDY_GO_SUPPLY_EARLY"; }
                    }
                } else {
                    output = greedyStepToNearestSupplyAdj(mapData, myPos, DIRS, MOVE_CMDS);
                    debugMode = "GREEDY_GO_SUPPLY_EARLY";
                }
            } else if (findFireLineOfSight(mapData, myPos, DIRS).canFire) {
                LoSResult los = findFireLineOfSight(mapData, myPos, DIRS);
            	int targetHp = getTargetHp(los.targetSymbol);
            	boolean useMega = myMega > 0 && targetHp > 30;
            	output = useMega ? FIRE_M_CMDS[los.dirIndex] : FIRE_CMDS[los.dirIndex];
                debugMode = useMega ? "FIRE_MEGA" : "FIRE_NORMAL";
            } else if (isAdjacentToSupply(mapData, myPos, DIRS)) {
            	// 2) 보급: 인접 시 코드 있으면 즉시 해독, 없으면 F 인접 유지(오빗)
            	boolean earlyPhase = turn < EARLY_SUPPLY_TURNS;
            	boolean needMega = (myMega < MEGA_TARGET_PER_TANK) && (estimateTeamMega() < TEAM_MEGA_MIN_RESERVE || earlyPhase) && (myMega < 10);
            	boolean codesAvailable = (codes != null && codes.length > 0);

                // 방어 위기 시: 안전 여유가 있으면 보급 해독 허용, 아니면 수비 라인 우선
                if (isDefenseEmergency(mapData)) {
                    if (codesAvailable && (myMega < MEGA_TARGET_PER_TANK) && canResupplyWhileDefending(mapData)) {
            		String cipher = codes[0].trim(); String plain = decodeCaesarShift(cipher, 9); output = "G " + plain;
                        debugMode = "SUPPLY_DECODE_DEF";
                    } else {
                        output = chooseDefenseLineMove(mapData, myPos, DIRS, MOVE_CMDS, buildThreatMap(mapData, DIRS));
                        debugMode = "DEF_LINE";
                    }
                } else {
                    // MAX 2발: 내 메가가 2 미만이고, 2턴 연속 접근이 아닐 때 해독 지속(또는 응급)
                    if (codesAvailable && (myMega < MEGA_TARGET_PER_TANK) && (emergencySupplyMode || enemyApproachStreak < 2)) {
                        String cipher = codes[0].trim(); String plain = decodeCaesarShift(cipher, 9); output = "G " + plain;
                    debugMode = "SUPPLY_DECODE";
            	} else {
                        if (myMega < MEGA_TARGET_PER_TANK) {
            		output = chooseSupplyOrbitMove(mapData, myPos, DIRS, MOVE_CMDS);
                            debugMode = "WAIT_FOR_CODE_OR_SAFE";
                        } else {
                            output = chooseRoleEngageMove(mapData, myPos, DIRS, MOVE_CMDS, buildThreatMap(mapData, DIRS), TankRole.SUPPORTER);
                            if (output == null) output = choosePatrolMove(mapData, myPos, DIRS, MOVE_CMDS, buildThreatMap(mapData, DIRS));
                            debugMode = "ENGAGE_OR_PATROL_NEAR_F";
                        }
                    }
            	}
            } else {
            	// 3) 이동: 필요 시 F로, 아니면 방어선 패트롤(정지 금지)
            	boolean[][] threat = buildThreatMap(mapData, DIRS);
            	boolean approaching = isEnemyApproaching(mapData, myPos);
            // 최근 교착 여부 갱신 (중복 제거)
            if (lastR != -1 && lastC != -1 && lastWasMove && myPos[0]==lastR && myPos[1]==lastC) {
                recentStuck = true;
            } else {
                recentStuck = false;
            }
            // 적 후퇴 추세(나 기준) 갱신
            curNearestEnemyDist = distanceToNearestEnemy(mapData, myPos);
            enemyRetreatingFlag = (lastNearestEnemyDist != -1 && curNearestEnemyDist > lastNearestEnemyDist);
            if (lastNearestEnemyDist != -1 && curNearestEnemyDist != -1 && curNearestEnemyDist < lastNearestEnemyDist) enemyApproachStreak++; else enemyApproachStreak = 0;

            // 교착 상황 및 보급 응급 상황 감지
            boolean isStalemate = updateStalemate(myPos, turn);
            boolean earlyPhase = turn < EARLY_SUPPLY_TURNS;
            boolean codesAvailable = (codes != null && codes.length > 0);
            boolean myTurnForSupply = isMySupplyTurn(turn);
            distToF = distanceToNearestSupplyAdjacency(mapData, myPos, DIRS);
            distToEnemy = distanceToNearestEnemy(mapData, myPos);
            int teamMegaTotal = estimateTeamMega();

            // 보급 상한: 2발 이상이면 보급 우선순위 자체 차단
            if (myMega >= MEGA_TARGET_PER_TANK) { codesAvailable = false; }

            // 극초반 강제 보급: MAX 2발 미만일 때만 이동
            if (turn < EARLY_SUPPLY_TURNS && myMega < MEGA_TARGET_PER_TANK) {
                Queue<String> pathToF = aStarToSupplyAdjacency(mapData, myPos, DIRS, MOVE_CMDS, threat, turn);
                if ((pathToF == null || pathToF.isEmpty()) || stuckTurnCount >= STALEMATE_THRESHOLD) {
                    Queue<String> reroute = rerouteToAllySideSupply(mapData, myPos, DIRS, MOVE_CMDS, threat);
                    if (reroute != null && !reroute.isEmpty()) {
                        output = reroute.poll();
                        debugMode = "REROUTE_SAFE_SUPPLY";
                    } else {
                        output = chooseSupplyOrbitMove(mapData, myPos, DIRS, MOVE_CMDS);
                        debugMode = "SUPPLY_ORBIT_WAIT";
                    }
                } else {
                    output = pathToF.poll();
                    debugMode = "GO_SUPPLY";
                }
            } else {
                // 새로운 보급 우선순위 계산 (상한 도달 시 사실상 false)
            boolean supplyPriority = calculateSupplyPriority(myShell, myMega, teamMegaTotal, isStalemate,
                                                            turn, codesAvailable, myTurnForSupply, earlyPhase,
                                                            distToF, distToEnemy, approaching, threat[myPos[0]][myPos[1]]);
                // 보급소는 점유 개념이 없으므로, 인접 슬롯이 비어있으면 라운드로빈 없이 즉시 보급 허용
                boolean needMega2 = (myMega < MEGA_TARGET_PER_TANK) && (teamMegaTotal < TEAM_MEGA_MIN_RESERVE || earlyPhase) && (myMega < 10);
                if (!supplyPriority && codesAvailable && needMega2 && hasFreeSupplyAdjacency(mapData, DIRS)) {
                    supplyPriority = true;
                }

            if (supplyPriority) {
                Queue<String> pathToF = aStarToSupplyAdjacency(mapData, myPos, DIRS, MOVE_CMDS, threat, turn);
                    if ((pathToF == null || pathToF.isEmpty()) || stuckTurnCount >= STALEMATE_THRESHOLD) {
                        Queue<String> reroute = rerouteToAllySideSupply(mapData, myPos, DIRS, MOVE_CMDS, threat);
                        if (reroute != null && !reroute.isEmpty()) {
                            output = reroute.poll();
                            debugMode = "REROUTE_SAFE_SUPPLY";
                        } else {
                            output = chooseSupplyOrbitMove(mapData, myPos, DIRS, MOVE_CMDS);
                            debugMode = "SUPPLY_ORBIT";
                        }
                    } else {
                        output = pathToF.poll();
                        debugMode = "EMERGENCY_SUPPLY";
                    }
                if (emergencySupplyMode) {
                    lastSupplyTurn = turn; // 응급 보급 시도 기록
                    System.out.println("[T2-EMERGENCY] Emergency supply mode activated! Risk level high.");
                }
            } else {
                emergencySupplyMode = false; // 응급 모드 해제
                    // 무적/무한 패트롤 상황: 적이 없고 교착이면 집결 후 랜덤 전진
                    if (stuckTurnCount >= STALEMATE_THRESHOLD && (distToEnemy < 0 || distToEnemy > 8)) {
                        int[] allyCent = computeAllyCentroid(mapData);
                        boolean[][] rvGoals = buildRendezvousGoals(mapData, allyCent, null);
                        if (rvGoals != null) {
                            int[] hPos = findAllyTurret(mapData);
                            Queue<String> path = aStarToGoals(mapData, myPos, rvGoals, DIRS, MOVE_CMDS, threat, hPos);
                            if (path != null && !path.isEmpty()) {
                                output = path.poll(); debugMode = "REGROUP";
                            } else {
                                String bo = chooseBreakoutMove(mapData, myPos, DIRS, MOVE_CMDS);
                                if (bo != null) { output = bo; debugMode = "BREAKOUT"; }
                                else { output = chooseRandomAdvanceMove(mapData, myPos, DIRS, MOVE_CMDS, turn); debugMode = "RANDOM_ADV"; }
                            }
                        } else {
                            String bo = chooseBreakoutMove(mapData, myPos, DIRS, MOVE_CMDS);
                            if (bo != null) { output = bo; debugMode = "BREAKOUT"; }
                            else { output = chooseRandomAdvanceMove(mapData, myPos, DIRS, MOVE_CMDS, turn); debugMode = "RANDOM_ADV"; }
                        }
                    } else if (stuckTurnCount >= STALEMATE_THRESHOLD && hasLocalSuperiority(mapData, myPos, 3) && isRearToTeam(mapData, myPos)) {
                        // 교착 반복 + 국지적 수적 우위 + 내가 후방이면 전진선 맞추고 공격
                        String act = chooseAggressiveAdvanceMove(mapData, myPos, DIRS, MOVE_CMDS);
                        if (act != null) { output = act; debugMode = "ADVANCE_LINE_ATTACK"; }
                        else { String a2 = chooseRoleEngageMove(mapData, myPos, DIRS, MOVE_CMDS, threat, TankRole.SUPPORTER); if (a2 == null) a2 = choosePatrolMove(mapData, myPos, DIRS, MOVE_CMDS, threat); output = a2; debugMode = "ENGAGE_OR_PATROL"; }
                    } else if (hasLocalSuperiority(mapData, myPos, 3)) {
                        // 수적 우위: 즉시 전진 공격
                        String act = chooseAggressiveAdvanceMove(mapData, myPos, DIRS, MOVE_CMDS);
                        if (act == null) act = chooseRoleEngageMove(mapData, myPos, DIRS, MOVE_CMDS, threat, TankRole.SUPPORTER);
                        if (act == null) act = choosePatrolMove(mapData, myPos, DIRS, MOVE_CMDS, threat);
                        output = act; debugMode = "NUM_ADV_ATTACK";
                    } else if (distToEnemy < 0 || distToEnemy > 8) {
                        // 주변 적 부재/원거리: 즉시 전진 탐색
                        String bo = chooseBreakoutMove(mapData, myPos, DIRS, MOVE_CMDS);
                        output = (bo != null) ? bo : chooseRandomAdvanceMove(mapData, myPos, DIRS, MOVE_CMDS, turn);
                        debugMode = (bo != null) ? "BREAKOUT" : "RANDOM_ADV";
                    } else {
                        // 끌어내기 접근(중거리): 선제턴 유리 유지하며 거리 4~6으로 접근
                        String tease = chooseTeaseApproachMove(mapData, myPos, DIRS, MOVE_CMDS, threat);
                        // 교전 루프 시 축 포위 포지셔닝 우선 시도
                        String axis = null;
                        if (stuckTurnCount >= STALEMATE_THRESHOLD || enemyNearby(mapData, myPos)) {
                            axis = moveToAxisEncirclement(mapData, myPos, DIRS, MOVE_CMDS, threat);
                        }
                        // 기본: 교전 → 패트롤
                        String act = (tease!=null) ? tease : (axis!=null ? axis : chooseRoleEngageMove(mapData, myPos, DIRS, MOVE_CMDS, threat, TankRole.SUPPORTER));
                        if (act == null) act = choosePatrolMove(mapData, myPos, DIRS, MOVE_CMDS, threat);
                        output = act; debugMode = (tease!=null? "TEASE_APPROACH" : (axis!=null? "AXIS_ENCIRCLE" : "ENGAGE_OR_PATROL"));
                    }
                }
            }
            }

            // 메인 프로그램에서 명령을 처리할 수 있도록 명령어를 submit()의 인자로 전달
            System.out.printf("[T2][turn=%d] pos=(%d,%d) action=%s mode=%s df=%d de=%d\n", turn, myPos[0], myPos[1], output, debugMode, distToF, distToEnemy);
            // 이동 기록 갱신
            lastWasMove = output.endsWith("A") && !output.contains(" F");
            if (lastWasMove) {
                char ch = output.charAt(0);
                lastMoveDir = (ch=='R'?0: ch=='D'?1: ch=='L'?2: 3);
            } else {
                lastMoveDir = -1;
            }
            lastR = myPos[0]; lastC = myPos[1];
            gameData = submit(output);
            lastNearestEnemyDist = curNearestEnemyDist;

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

    private static int parseIntSafe(String s) {
    	if (s == null) return 0;
    	try { return Integer.parseInt(s); } catch (Exception ex) { return 0; }
    }

    private static class LoSResult {
    	boolean canFire; int dirIndex; String targetSymbol;
    	LoSResult(boolean a, int b, String c){ this.canFire=a; this.dirIndex=b; this.targetSymbol=c; }
    }

    private static LoSResult findFireLineOfSight(String[][] grid, int[] start, int[][] dirs) {
    	int rows = grid.length, cols = grid[0].length;
            for (int d = 0; d < dirs.length; d++) {
    		int r = start[0], c = start[1];
    		for (int k = 1; k <= 3; k++) {
    			r += dirs[d][0]; c += dirs[d][1];
    			if (r < 0 || r >= rows || c < 0 || c >= cols) break;
    			String cell = grid[r][c]; if (cell == null) break;
                // 차폐물: R/T/F
                if (cell.equals("R") || cell.equals("T") || cell.equals("F")) break;
                // 아군 유닛 차폐: M/H/M1/M2 등
                if (cell.equals("M") || cell.equals("H") || cell.startsWith("M")) break;
    			if (cell.equals("X") || cell.startsWith("E")) return new LoSResult(true, d, cell);
    		}
    	}
    	return new LoSResult(false, -1, null);
    }

    private static int getTargetHp(String symbol) {
    	if (symbol == null) return 0; String[] info = enemies.get(symbol);
    	if (info == null && symbol.equals("X")) info = enemies.get("X");
    	if (info == null) return 0; return parseIntSafe(info[0]);
    }

    private static boolean isEnemyApproaching(String[][] grid, int[] myPos){
    	int rows=grid.length, cols=grid[0].length; for(int r=0;r<rows;r++){ for(int c=0;c<cols;c++){ String cell=grid[r][c]; if(cell==null) continue; if(cell.equals("X")||cell.startsWith("E")){ int dist=Math.abs(r-myPos[0])+Math.abs(c-myPos[1]); if(dist<=4) return true; } } } return false; }

    private static int getMyMegaCap(String[] me){ return 10; }

    private static int estimateTeamMega(){ int total=0; for(String key: myAllies.keySet()){ String[] val=myAllies.get(key); if(val==null) continue; if(key.equals("M")||key.startsWith("M")){ if(val.length>=4) total+=parseIntSafe(val[3]); } } return total; }

    private static boolean[][] buildThreatMap(String[][] grid, int[][] dirs) {
    	int rows=grid.length, cols=grid[0].length; boolean[][] t=new boolean[rows][cols];
        for(int r=0;r<rows;r++){ for(int c=0;c<cols;c++){ String cell=grid[r][c]; if(cell==null) continue; if(cell.equals("X")||cell.startsWith("E")){
            for(int d=0; d<dirs.length; d++){ int nr=r, nc=c; for(int k=1;k<=3;k++){ nr+=dirs[d][0]; nc+=dirs[d][1]; if(nr<0||nr>=rows||nc<0||nc>=cols) break; String cc=grid[nr][nc]; if(cc==null) break; if(cc.equals("R")||cc.equals("T")||cc.equals("F")) break; if(cc.equals("M")||cc.equals("H")||cc.startsWith("M")||cc.startsWith("E")||cc.equals("X")) break; t[nr][nc]=true; } }
            // 다음 1스텝 예측 위협
            for(int nd=0; nd<dirs.length; nd++){ int ar=r+dirs[nd][0], ac=c+dirs[nd][1]; if(ar<0||ar>=rows||ac<0||ac>=cols) continue; String cc2=grid[ar][ac]; if(cc2==null) continue; if(cc2.equals("R")||cc2.equals("T")||cc2.equals("F")) continue; t[ar][ac]=true; }
        } }
        }
    	return t;
    }

    private static String choosePatrolMove(String[][] grid, int[] start, int[][] dirs, String[] moveCmds, boolean[][] threat){
        int rows=grid.length, cols=grid[0].length; int[] hPos=findAllyTurret(grid); int[] eCent=findEnemyCentroid(grid); boolean preferVertical=preferVerticalPatrol(hPos,eCent);
    	int[] order = preferVertical ? new int[]{3,1,2,0} : new int[]{2,0,3,1};
        boolean defenseMode = isDefenseMode(grid) && stuckTurnCount < STALEMATE_THRESHOLD; int[] nearestToH=findNearestEnemyToH(grid); int[] nearestToMe=findNearestEnemyToMe(grid, start);
        int best=-1; int bestScore=Integer.MIN_VALUE;
        for(int oi=0; oi<order.length; oi++){
            int d=order[oi]; int nr=start[0]+dirs[d][0], nc=start[1]+dirs[d][1];
            if(nr<0||nr>=rows||nc<0||nc>=cols) continue; String cell=grid[nr][nc]; if(!isWalkable(cell)) continue; if(isBlockedByAllyTurretZone(grid,nr,nc)) continue;
            int score=0;
            if(!threat[nr][nc]) score+=50; else score-=50;
            if(isAdjacentToCover(grid,new int[]{nr,nc},dirs)) score+=20;
            boolean along=((preferVertical&&(d==3||d==1))||(!preferVertical&&(d==2||d==0))); if(along) score+=10;
            if("S".equals(cell)) score-=10;
            if(hPos!=null){ int distFromH=Math.abs(nr-hPos[0])+Math.abs(nc-hPos[1]); if(defenseMode){ if(distFromH<=6) score+=50; else score-=100; } else { if(distFromH>6) score-=30; } }
            if(defenseMode && nearestToH!=null){ int de=Math.abs(nr-nearestToH[0])+Math.abs(nc-nearestToH[1]); score += Math.max(0,6-de)*3; }
            if (nearestToMe!=null){
                int curDist=Math.abs(start[0]-nearestToMe[0])+Math.abs(start[1]-nearestToMe[1]);
                int nextDist=Math.abs(nr-nearestToMe[0])+Math.abs(nc-nearestToMe[1]);

                // 안전 게릴라 로직: 내가 선공 가능한지 체크
                boolean canFirstStrike = iCanFirstStrikeFrom(grid,new int[]{nr,nc},dirs);

                // 추격 대상 외 다른 적들의 선공각에 있는지 체크
                boolean inOtherEnemyRange = isInOtherEnemiesFirstStrikeLine(grid,nr,nc,nearestToMe,dirs);

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
            if(score>bestScore){ bestScore=score; best=d; }
        }
    	return best==-1 ? moveCmds[preferVertical?3:2] : moveCmds[best];
    }

    private static boolean isAdjacentToCover(String[][] grid, int[] pos, int[][] dirs){ int rows=grid.length, cols=grid[0].length; for(int d=0; d<dirs.length; d++){ int r=pos[0]+dirs[d][0], c=pos[1]+dirs[d][1]; if(r<0||r>=rows||c<0||c>=cols) continue; String cell=grid[r][c]; if("R".equals(cell)||"T".equals(cell)) return true; } return false; }
    private static int[] findNearestEnemyToMe(String[][] grid, int[] me){ int rows=grid.length, cols=grid[0].length; int best=Integer.MAX_VALUE; int[] pos=null; for(int r=0;r<rows;r++){ for(int c=0;c<cols;c++){ String cell=grid[r][c]; if(cell==null) continue; if(cell.equals("X")||cell.startsWith("E")){ int d=Math.abs(me[0]-r)+Math.abs(me[1]-c); if(d<best){ best=d; pos=new int[]{r,c}; } } } } return pos; }
    private static boolean isTileInEnemyFirstStrikeLine(String[][] grid, int r, int c, int[][] dirs){ int rows=grid.length, cols=grid[0].length; for(int er=0; er<rows; er++){ for(int ec=0; ec<cols; ec++){ String cell=grid[er][ec]; if(cell==null) continue; if(cell.equals("X")||cell.startsWith("E")){ for(int d=0; d<4; d++){ int nr=er, nc=ec; for(int k=1;k<=3;k++){ nr+=dirs[d][0]; nc+=dirs[d][1]; if(nr<0||nr>=rows||nc<0||nc>=cols) break; String cc=grid[nr][nc]; if(cc==null) break; if(cc.equals("R")||cc.equals("T")||cc.equals("F")) break; if(cc.equals("M")||cc.equals("H")||cc.startsWith("M")||cc.startsWith("E")||cc.equals("X")) break; if(nr==r&&nc==c) return true; } } } } } return false; }
    private static boolean iCanFirstStrikeFrom(String[][] grid, int[] pos, int[][] dirs){ LoSResult los=findFireLineOfSight(grid,pos,dirs); return los.canFire; }

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
    private static boolean isDefenseMode(String[][] grid){ int[] h=findAllyTurret(grid); if(h==null) return false; int hHp=getAllyTurretHp(); if(hHp>0&&hHp<=30) return true; int[] ne=findNearestEnemyToH(grid); if(ne==null) return false; if (defenseProactiveFlag) return true; int d=Math.abs(h[0]-ne[0])+Math.abs(h[1]-ne[1]); if (d<=PROACTIVE_DEF_DIST) return true; return d<=4; }
    private static int getAllyTurretHp(){ String sym=getAllyTurretSymbol(); String[] h=myAllies.get(sym); if(h==null||h.length==0) return 0; return parseIntSafe(h[0]); }
    private static int[] findNearestEnemyToH(String[][] grid){ int[] h=findAllyTurret(grid); if(h==null) return null; int rows=grid.length, cols=grid[0].length; int best=Integer.MAX_VALUE; int[] bestPos=null; for(int r=0;r<rows;r++){ for(int c=0;c<cols;c++){ String cell=grid[r][c]; if(cell==null) continue; if(cell.equals("X")||cell.startsWith("E")){ int d=Math.abs(h[0]-r)+Math.abs(h[1]-c); if(d<best){ best=d; bestPos=new int[]{r,c}; } } } } return bestPos; }
    // (중복 제거) findAllyTurret는 아래 A* 유틸 섹션에서 정의됩니다.
    private static int[] findEnemyCentroid(String[][] grid){ int sumR=0,sumC=0,cnt=0; for(int r=0;r<grid.length;r++){ for(int c=0;c<grid[0].length;c++){ String cell=grid[r][c]; if(cell==null) continue; if(cell.equals("X")||cell.startsWith("E")){ sumR+=r; sumC+=c; cnt++; } } } if(cnt==0) return new int[]{grid.length/2, grid[0].length/2}; return new int[]{sumR/cnt, sumC/cnt}; }
    private static boolean preferVerticalPatrol(int[] hPos,int[] eCent){ if(hPos==null||eCent==null) return false; int dx=eCent[1]-hPos[1]; int dy=eCent[0]-hPos[0]; return Math.abs(dx)>=Math.abs(dy); }

    // 국지적 수적 우위 판단: 반경 r 내 아군(M, M2, M3) 수 >= 적(E*, X) 수 + 1
    private static boolean hasLocalSuperiority(String[][] grid, int[] center, int radius){
        int rows=grid.length, cols=grid[0].length; int allies=0, enemiesCnt=0;
        for(int r=center[0]-radius; r<=center[0]+radius; r++){
            for(int c=center[1]-radius; c<=center[1]+radius; c++){
                if(r<0||r>=rows||c<0||c>=cols) continue; String cell=grid[r][c]; if(cell==null) continue;
                if(cell.equals("M")||cell.startsWith("M")) allies++;
                else if(cell.equals("X")||cell.startsWith("E")) enemiesCnt++;
            }
        }
        return allies >= enemiesCnt + 1;
    }

    // 내가 팀의 후방인지: 아군 중심과 적 중심을 잇는 선에서 뒤쪽(아군 중심 쪽)에 위치
    private static boolean isRearToTeam(String[][] grid, int[] me){
        int[] allyCent = computeAllyCentroid(grid); int[] enemyCent = findEnemyCentroid(grid);
        if(allyCent==null||enemyCent==null) return false;
        int dMe = Math.abs(me[0]-enemyCent[0])+Math.abs(me[1]-enemyCent[1]);
        int dAlly = Math.abs(allyCent[0]-enemyCent[0])+Math.abs(allyCent[1]-enemyCent[1]);
        return dMe > dAlly; // 내가 더 뒤(적에서 더 멀다)
    }

    // 전진선 맞추기: 적 방향으로 1칸 당겨 전면 형성(위협 과도 타일은 피함)
    private static String chooseAggressiveAdvanceMove(String[][] grid, int[] myPos, int[][] dirs, String[] moveCmds){
        int[] enemy = findNearestEnemyToMe(grid, myPos); if(enemy==null) return null;
        int best=-1, bestScore=Integer.MIN_VALUE; for(int d=0; d<4; d++){
            int nr=myPos[0]+dirs[d][0], nc=myPos[1]+dirs[d][1];
            if(nr<0||nr>=grid.length||nc<0||nc>=grid[0].length) continue; String cell=grid[nr][nc]; if(!isWalkable(cell)) continue; if(isBlockedByAllyTurretZone(grid,nr,nc)) continue;
            int curDist=Math.abs(myPos[0]-enemy[0])+Math.abs(myPos[1]-enemy[1]);
            int nextDist=Math.abs(nr-enemy[0])+Math.abs(nc-enemy[1]);
            int score=0; if(nextDist<curDist) score+=30; if("S".equals(cell)) score-=5; // 약한 모래 패널티
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

    // 교착 돌파 전진: 위협이 덜하고 H에서 멀어지는 쪽으로 전진 우선
    private static String chooseBreakoutMove(String[][] grid, int[] myPos, int[][] dirs, String[] moveCmds){
        int[] h = findAllyTurret(grid);
        int best=-1, bestScore=Integer.MIN_VALUE;
        for(int d=0; d<4; d++){
            int nr=myPos[0]+dirs[d][0], nc=myPos[1]+dirs[d][1];
            if(nr<0||nr>=grid.length||nc<0||nc>=grid[0].length) continue; String cell=grid[nr][nc];
            if(!isWalkable(cell)) continue; if(isBlockedByAllyTurretZone(grid,nr,nc)) continue;
            int score=0;
            // H에서 멀어지면 가산(집결 고착 방지)
            if(h!=null){ int cur=Math.abs(myPos[0]-h[0])+Math.abs(myPos[1]-h[1]); int nxt=Math.abs(nr-h[0])+Math.abs(nc-h[1]); if(nxt>cur) score+=15; else score-=5; }
            // 모래 약한 패널티
            if("S".equals(cell)) score-=3;
            if(score>bestScore){ bestScore=score; best=d; }
        }
        return best==-1? null : moveCmds[best];
    }

    private static boolean isAdjacentToSupply(String[][] grid, int[] pos, int[][] dirs) {
    	int rows = grid.length, cols = grid[0].length;
            for (int d = 0; d < dirs.length; d++) {
    		int r = pos[0] + dirs[d][0]; int c = pos[1] + dirs[d][1];
    		if (r < 0 || r >= rows || c < 0 || c >= cols) continue;
    		if ("F".equals(grid[r][c])) return true;
    	}
    	return false;
    }

    // 아군 포탑(H) 기준 -2..+2 권역(정사각형)을 벽으로 간주
    private static boolean isBlockedByAllyTurretZone(String[][] grid, int r, int c){
    	int[] h = findAllyTurret(grid);
    	if(h==null) return false;
    	int dr = Math.abs(r - h[0]);
    	int dc = Math.abs(c - h[1]);
    	return dr <= 2 && dc <= 2;
    }

    // 보급소 인접 슬롯이 하나라도 비어있는지(보행 가능) 확인
    private static boolean hasFreeSupplyAdjacency(String[][] grid, int[][] dirs){
    	int rows=grid.length, cols=grid[0].length;
    	for(int r=0;r<rows;r++){
    		for(int c=0;c<cols;c++){
    			if (!"F".equals(grid[r][c])) continue;
    			for(int d=0; d<4; d++){
    				int nr=r+dirs[d][0], nc=c+dirs[d][1];
    				if(nr<0||nr>=rows||nc<0||nc>=cols) continue;
    				String cell=grid[nr][nc];
    				if(cell==null) continue;
    				if(isWalkable(cell)) return true; // 누가 점유하지 않았다면 사용 가능
    			}
    		}
    	}
    	return false;
    }

    // 보급 재경로: 적 위협을 피하고 아군 쪽으로 우회하여 다른 F 인접칸 목표 생성
    private static Queue<String> rerouteToAllySideSupply(String[][] grid, int[] start, int[][] dirs, String[] moveCmds, boolean[][] threat){
        int rows=grid.length, cols=grid[0].length;
        int[] allyCent = computeAllyCentroid(grid);
        boolean[][] goals = new boolean[rows][cols]; boolean any=false;
        for(int r=0;r<rows;r++){
            for(int c=0;c<cols;c++){
                if(!"F".equals(grid[r][c])) continue;
                // 아군 중심과의 거리가 가까운 F 우선: allyCent 기준으로 가까운 F 인접만 허용
                for(int d=0; d<4; d++){
                    int nr=r+dirs[d][0], nc=c+dirs[d][1];
                    if(nr<0||nr>=rows||nc<0||nc>=cols) continue;
                    String cell=grid[nr][nc]; if(cell==null) continue;
                    if(!isWalkable(cell)) continue;
                    if(isBlockedByAllyTurretZone(grid,nr,nc)) continue;
                    if(threat!=null && threat[nr][nc]) continue; // 적과 마주치지 않는(위협 회피)
                    // 아군 중심과 가깝다면 가중 허용
                    if(allyCent==null || (Math.abs(nr-allyCent[0])+Math.abs(nc-allyCent[1]) <= Math.max(rows,cols))){
                        goals[nr][nc]=true; any=true;
                    }
                }
            }
        }
        if(!any) return null;
        int[] hPos = findAllyTurret(grid);
        return aStarToGoals(grid, start, goals, dirs, moveCmds, threat, hPos);
    }

    // 보급소 인접 타일들 중 하나를 목표로 A* 이동 경로 산출(가중치 적용)
    private static Queue<String> aStarToSupplyAdjacency(String[][] grid, int[] start, int[][] dirs, String[] moveCmds, boolean[][] threat, int turn) {
        int rows=grid.length, cols=grid[0].length; boolean[][] goals=new boolean[rows][cols];
        int[] fPos=findNearestSupply(grid, start);
        int preferredSlot=(getClientIndex() + (turn & 3))%4; // 턴 회전으로 충돌 분산
        if(fPos!=null){
            int[] slotOrder=new int[]{preferredSlot,(preferredSlot+1)%4,(preferredSlot+2)%4,(preferredSlot+3)%4};
            for(int si=0; si<4; si++){
                int d=slotOrder[si]; int nr=fPos[0]+dirs[d][0], nc=fPos[1]+dirs[d][1];
                if(nr<0||nr>=rows||nc<0||nc>=cols) continue;
                if(isWalkable(grid[nr][nc]) || (nr==start[0]&&nc==start[1])) goals[nr][nc]=true;
                if(si==0 && goals[nr][nc]) break;
            }
        }
        boolean any=false; for(int r=0;r<rows;r++){ for(int c=0;c<cols;c++){ if(goals[r][c]){ any=true; break; } } if(any) break; }
        if(!any){ for(int r=0;r<rows;r++){ for(int c=0;c<cols;c++){ if(!"F".equals(grid[r][c])) continue; for(int d=0; d<dirs.length; d++){ int nr=r+dirs[d][0], nc=c+dirs[d][1]; if(nr<0||nr>=rows||nc<0||nc>=cols) continue; if(isWalkable(grid[nr][nc]) || (nr==start[0]&&nc==start[1])) goals[nr][nc]=true; } } } }
        int[] hPos=findAllyTurret(grid);
        return aStarToGoals(grid, start, goals, dirs, moveCmds, threat, hPos);
    }

    private static Queue<String> aStarToGoals(String[][] grid, int[] start, boolean[][] goals, int[][] dirs, String[] moveCmds, boolean[][] threat, int[] hPos){
        int rows=grid.length, cols=grid[0].length; PriorityQueue<AStarNode> open=new PriorityQueue<>(Comparator.comparingInt(n->n.f));
        Map<String,Integer> bestG=new HashMap<>(); Map<String,String> came=new HashMap<>(); Map<String,Integer> cameDir=new HashMap<>();
        String sk=start[0]+","+start[1]; open.offer(new AStarNode(start[0],start[1],0,heuristicToGoals(start[0],start[1],goals))); bestG.put(sk,0);
        while(!open.isEmpty()){
            AStarNode cur=open.poll(); if(goals[cur.r][cur.c]) return reconstructPath(came,cameDir,cur.r,cur.c,sk,moveCmds);
            for(int d=0; d<dirs.length; d++){ int nr=cur.r+dirs[d][0], nc=cur.c+dirs[d][1]; if(nr<0||nr>=rows||nc<0||nc>=cols) continue; String cell=grid[nr][nc]; if(cell==null) continue; if(!isWalkable(cell)) continue; if(isBlockedByAllyTurretZone(grid,nr,nc)) continue; int step=weightedTileCost(grid,nr,nc,threat,hPos); int ng=cur.g+step; String k=nr+","+nc; Integer pg=bestG.get(k); if(pg==null||ng<pg){ bestG.put(k,ng); came.put(k,cur.r+","+cur.c); cameDir.put(k,d); int h=heuristicToGoals(nr,nc,goals); open.offer(new AStarNode(nr,nc,ng,ng+h)); } }
        }
        return new LinkedList<>();
    }

    // 역할 기반 교전 이동: 간단 버전 (목표 = 최근접 적의 측면/후방, 또는 H 근처 집결)
    private static String chooseRoleEngageMove(String[][] grid, int[] myPos, int[][] dirs, String[] moveCmds, boolean[][] threat, TankRole role){
    	int[] enemy = findNearestEnemyToMe(grid, myPos);
    	int[] hPos = findAllyTurret(grid);
    	if (enemy == null && hPos == null) return null;

    	boolean[][] goals = new boolean[grid.length][grid[0].length];

            if (enemy != null){
            	// 목표 타일 집합 구성: 적 인접 4방 중 보행 가능하고 안전한 곳(위험 타일은 국지 우위/팀 장비 우위 시 허용)
    		for(int d=0; d<4; d++){
    			int tr = enemy[0] + dirs[d][0];
    			int tc = enemy[1] + dirs[d][1];
    			if(tr<0||tr>=grid.length||tc<0||tc>=grid[0].length) continue;
    			String cell = grid[tr][tc];
    			if(cell==null) continue;
    			if(!isWalkable(cell)) continue;
				boolean allowRisk = hasLocalSuperiority(grid, myPos, 3) || estimateTeamMega() >= 3;
				if(threat!=null && threat[tr][tc] && !allowRisk) continue; // 위험 허용 조건 아닐 때만 배제
    			goals[tr][tc] = true;
    		}
    	}

    	// 적 목표가 없거나 목표가 비어있지 않다면 H 인근 집결지 보조 목표
    	boolean any=false; for(int r=0;r<goals.length;r++){ for(int c=0;c<goals[0].length;c++){ if(goals[r][c]){ any=true; break; } } if(any) break; }
    	if(!any && hPos != null){
    		// H 반경 2~3 링의 안전 격자에 집결
    		for(int r=hPos[0]-3;r<=hPos[0]+3;r++){
    			for(int c=hPos[1]-3;c<=hPos[1]+3;c++){
    				if(r<0||r>=grid.length||c<0||c>=grid[0].length) continue;
    				int d = Math.abs(r-hPos[0]) + Math.abs(c-hPos[1]);
    				if(d<2 || d>3) continue;
    				String cell = grid[r][c]; if(cell==null) continue; if(!isWalkable(cell)) continue;
    				if(threat!=null && threat[r][c]) continue;
    				goals[r][c] = true;
    			}
    		}
    	}

    	Queue<String> path = aStarToGoals(grid, myPos, goals, dirs, moveCmds, threat, hPos);
    	if(path==null || path.isEmpty()) return null;
    	return path.poll();
    }

    private static int heuristicToGoals(int r,int c, boolean[][] goals){ int rows=goals.length, cols=goals[0].length; int best=Integer.MAX_VALUE; for(int i=0;i<rows;i++){ for(int j=0;j<cols;j++){ if(!goals[i][j]) continue; int d=Math.abs(i-r)+Math.abs(j-c); if(d<best) best=d; } } return best==Integer.MAX_VALUE?0:best; }
    private static int weightedTileCost(String[][] grid,int r,int c, boolean[][] threat,int[] hPos){ int cost=1; if(threat!=null && threat[r][c]) cost+=50; if("S".equals(grid[r][c])) cost+=10; if(hPos!=null){ int dist=Math.abs(r-hPos[0])+Math.abs(c-hPos[1]); if(defenseProactiveFlag){ if(dist>6) cost+=60; } else { if(dist>6){ int extra=30; String[] me=myAllies.get("M"); int myMega=(me!=null&&me.length>=4)? parseIntSafe(me[3]) : 0; if(myMega>=2) extra=10; cost+=extra; } } } return cost; }
    private static Queue<String> reconstructPath(Map<String,String> came, Map<String,Integer> cameDir,int er,int ec,String sk,String[] moveCmds){ LinkedList<String> path=new LinkedList<>(); String cur=er+","+ec; while(!cur.equals(sk)){ Integer d=cameDir.get(cur); if(d==null) break; path.addFirst(moveCmds[d]); cur=came.get(cur); if(cur==null) break; } return path; }
    private static class AStarNode{ int r,c,g,f; AStarNode(int r,int c,int g,int f){this.r=r;this.c=c;this.g=g;this.f=f;} }

    private static int[] findAllyTurret(String[][] grid){ String sym=getAllyTurretSymbol(); for(int r=0;r<grid.length;r++){ for(int c=0;c<grid[0].length;c++){ if(sym.equals(grid[r][c])) return new int[]{r,c}; } } return null; }
    private static String getAllyTurretSymbol(){ if(myAllies.containsKey("H")) return "H"; if(myAllies.containsKey("X")) return "X"; return "H"; }
    private static int getMinDistEnemyToH(String[][] grid){ int[] h=findAllyTurret(grid); if(h==null) return -1; int rows=grid.length, cols=grid[0].length; int best=Integer.MAX_VALUE; boolean found=false; for(int r=0;r<rows;r++){ for(int c=0;c<cols;c++){ String cell=grid[r][c]; if(cell==null) continue; if(cell.equals("X")||cell.startsWith("E")){ int d=Math.abs(h[0]-r)+Math.abs(h[1]-c); if(d<best){ best=d; found=true; } } } } return found?best:-1; }
    private static String decodeCaesarShift(String cipher, int shift) {
    	StringBuilder sb = new StringBuilder();
    	for (int i=0;i<cipher.length();i++){
    		char ch=cipher.charAt(i);
    		if (ch>='A'&&ch<='Z'){ int v=ch-'A'; int nv=(v+shift)%26; sb.append((char)('A'+nv)); }
    		else sb.append(ch);
    	}
    	return sb.toString();
    }

    // (제거됨) 기존 BFS 유틸은 A* 전환으로 미사용

    private static boolean isWalkable(String cell){ return "G".equals(cell) || "S".equals(cell); }

    // F 인접 유지(오빗)
    private static String chooseSupplyOrbitMove(String[][] grid, int[] start, int[][] dirs, String[] moveCmds){
        int rows=grid.length, cols=grid[0].length; int[] f=findNearestSupply(grid, start); if(f==null) return moveCmds[0];
        int[] order=new int[]{0,1,2,3}; int best=-1; int bestScore=Integer.MIN_VALUE;
        for(int oi=0; oi<4; oi++){
            int d=order[oi]; int nr=start[0]+dirs[d][0], nc=start[1]+dirs[d][1];
            if(nr<0||nr>=rows||nc<0||nc>=cols) continue; String cell=grid[nr][nc]; if(!isWalkable(cell)) continue;
            int score=0; boolean keepAdj=false; for(int k=0;k<4;k++){ int ar=nr+dirs[k][0], ac=nc+dirs[k][1]; if(ar<0||ar>=rows||ac<0||ac>=cols) continue; if("F".equals(grid[ar][ac])){ keepAdj=true; break; } }
            if(keepAdj) score+=10; else score-=50; if("S".equals(cell)) score-=5;
            // 최근 교착이면 직전 방향은 약한 패널티로 회피
            if (recentStuck && lastMoveDir==d) score -= 8;
            if(score>bestScore){ bestScore=score; best=d; }
        }
        return best==-1? moveCmds[0] : moveCmds[best];
    }

    // 초반 질주: 가장 가까운 F 인접 목표로 직선 한 스텝(위협/가중치 무시)
    private static String greedyStepToNearestSupplyAdj(String[][] grid, int[] start, int[][] dirs, String[] moveCmds){
        int[] f = findNearestSupply(grid, start); if(f==null) return moveCmds[0];
        // F 인접 목표들 중 현재 위치에서 맨해튼 최소인 목표 하나 선택
        int bestDist=Integer.MAX_VALUE; int[] goal=null;
        for(int d=0; d<4; d++){
            int gr=f[0]+dirs[d][0], gc=f[1]+dirs[d][1];
            if(gr<0||gr>=grid.length||gc<0||gc>=grid[0].length) continue;
            if(!isWalkable(grid[gr][gc])) continue; if(isBlockedByAllyTurretZone(grid,gr,gc)) continue;
            int dist=Math.abs(start[0]-gr)+Math.abs(start[1]-gc);
            if(dist<bestDist){ bestDist=dist; goal=new int[]{gr,gc}; }
        }
        if(goal==null) return moveCmds[0];
        // 목표까지 거리를 줄이는 한 스텝 선택(축 차이가 큰 축 우선)
        int dr = goal[0]-start[0]; int dc = goal[1]-start[1];
        int[] order = (Math.abs(dr) >= Math.abs(dc))
                ? (dr>0? new int[]{1, 0, 2, 3} : new int[]{3, 0, 2, 1}) // D/U 우선, 다음 R/L
                : (dc>0? new int[]{0, 1, 3, 2} : new int[]{2, 1, 3, 0}); // R/L 우선, 다음 D/U
        for(int oi=0; oi<4; oi++){
            int d = order[oi]; int nr=start[0]+dirs[d][0], nc=start[1]+dirs[d][1];
            if(nr<0||nr>=grid.length||nc<0||nc>=grid[0].length) continue; String cell=grid[nr][nc];
            if(!isWalkable(cell)) continue; if(isBlockedByAllyTurretZone(grid,nr,nc)) continue;
            return moveCmds[d];
        }
        return moveCmds[0];
    }

    private static int[] findNearestSupply(String[][] grid, int[] start){ int rows=grid.length, cols=grid[0].length; int best=Integer.MAX_VALUE; int[] pos=null; for(int r=0;r<rows;r++){ for(int c=0;c<cols;c++){ if("F".equals(grid[r][c])){ int d=Math.abs(r-start[0])+Math.abs(c-start[1]); if(d<best){ best=d; pos=new int[]{r,c}; } } } } return pos; }
    private static int distanceToNearestSupplyAdjacency(String[][] grid, int[] start, int[][] dirs){ int[] f=findNearestSupply(grid,start); if(f==null) return -1; int best=Integer.MAX_VALUE; int rows=grid.length, cols=grid[0].length; for(int d=0; d<4; d++){ int nr=f[0]+dirs[d][0], nc=f[1]+dirs[d][1]; if(nr<0||nr>=rows||nc<0||nc>=cols) continue; if(isWalkable(grid[nr][nc]) || (nr==start[0]&&nc==start[1])){ int dist=Math.abs(start[0]-nr)+Math.abs(start[1]-nc); if(dist<best) best=dist; } } return best==Integer.MAX_VALUE? -1: best; }
    private static int distanceToNearestEnemy(String[][] grid, int[] start){ int rows=grid.length, cols=grid[0].length; int best=Integer.MAX_VALUE; boolean found=false; for(int r=0;r<rows;r++){ for(int c=0;c<cols;c++){ String cell=grid[r][c]; if(cell==null) continue; if(cell.startsWith("E")||cell.equals("X")){ int d=Math.abs(start[0]-r)+Math.abs(start[1]-c); if(d<best){ best=d; found=true; } } } } return found?best:-1; }
    private static int getClientIndex(){ if(ARGS==null||ARGS.isEmpty()) return 0; int v=0; for(int i=0;i<ARGS.length();i++){ char ch=ARGS.charAt(i); if(ch>='0'&&ch<='9'){ v=v*10+(ch-'0'); } else break; } return v; }
    private static boolean isMySupplyTurn(int turn){ int idx=getClientIndex()%3; return (turn%3)==(idx%3); }

    // (제거됨) 기존 BFS 유틸은 A* 전환으로 미사용

    // (제거됨) 기존 BFS 헬퍼 클래스는 A* 전환으로 미사용
    
	////////////////////////////////////
	// 알고리즘 함수/메서드 부분 구현 끝
	////////////////////////////////////
    
    ///////////////////////////////
    // 메인 프로그램 통신 메서드 정의
    ///////////////////////////////

    // 메인 프로그램 연결 및 초기화
    private static String init(String nickname) {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(HOST, PORT));
            String initCommand = "INIT " + nickname;

            return submit(initCommand);
        } catch (Exception e) {
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
                close();
                return null;
            }

            String gameData = new String(bytes, 0, length, "UTF-8");
            if (gameData.length() > 0 && gameData.charAt(0) >= '1' && gameData.charAt(0) <= '9') {
                return gameData;
            }

            close();
            return null;
        } catch (Exception e) {
        }
        return null;
    }

    // 연결 해제
    private static void close() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception e) {
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
    private static void printData(String gameData) { }

    // ===== 팀 협동 시스템 =====

    // 역할 정의
    enum TankRole {
        AGGRESSOR,    // 주공격수: 가장 적에게 가까운 탱크
        FLANKER,      // 측면공격: 적의 측면/후방 담당
        SUPPORTER,    // 지원수: 상황에 따라 지원 또는 H 보호
        INTERCEPTOR   // 차단수: 적의 도주로 차단
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
    }

    // 팀 협동 이동 메인 함수
    private static String chooseTeamCoordinatedMove(String[][] grid, int[] myPos, int[][] dirs, String[] moveCmds, boolean[][] threat) {
        // Tank2는 기본적으로 지원수 역할
        TankRole myRole = TankRole.SUPPORTER;

        // 간단한 역할 결정 로직
        int[] enemyPos = findNearestEnemyToMe(grid, myPos);
        if (enemyPos != null) {
            int distToEnemy = TeamSituationAnalyzer.getDistance(myPos, enemyPos);
            if (distToEnemy <= 5) {
                myRole = TankRole.FLANKER; // 적과 가까우면 측면공격
            }
        }

        // 0) 팀 집결: 아군 중심 링(2~3) 다중 목표 설정 후 접근
        int[] allyCent = computeAllyCentroid(grid);
        boolean[][] rvGoals = buildRendezvousGoals(grid, allyCent, enemyPos);
        if (rvGoals != null) {
            int distToRv = distanceToGoals(myPos, rvGoals);
            if (enemyPos == null || distToRv > 1) {
                int[] hPos = findAllyTurret(grid);
                Queue<String> path = aStarToGoals(grid, myPos, rvGoals, dirs, moveCmds, threat, hPos);
                if (path != null && !path.isEmpty()) {
                    String towardRv = path.poll();
                    System.out.printf("[T2-TEAM] rendezvous-ring action=%s\n", towardRv);
                    return towardRv;
                }
            }
        }

        // 1) 교전 결정 시: 턴-거리 기반 후보 선택, 아니면 역할 기반 교전
        String action;
        if (enemyPos != null && shouldEngageNow(grid, myPos)){
            int bestDir=-1; int bestMyTurns=Integer.MAX_VALUE; int bestMargin=Integer.MIN_VALUE; int bestEnemyDist=Integer.MAX_VALUE;
            for(int d=0; d<4; d++){
                int nr=myPos[0]+dirs[d][0], nc=myPos[1]+dirs[d][1];
                if(nr<0||nr>=grid.length||nc<0||nc>=grid[0].length) continue; String cell=grid[nr][nc]; if(!isWalkable(cell)) continue; if(isBlockedByAllyTurretZone(grid,nr,nc)) continue;
                int myTurns = turnsToShoot(grid, new int[]{nr,nc}, enemyPos, dirs, 12);
                int enemyTurns = turnsToShootIgnoreZone(grid, enemyPos, new int[]{nr,nc}, dirs, 12);
                int margin = (enemyTurns==Integer.MAX_VALUE?50:enemyTurns) - (myTurns==Integer.MAX_VALUE?50:myTurns);
                int enemyDist = Math.abs(nr-enemyPos[0])+Math.abs(nc-enemyPos[1]);
                if (myTurns<bestMyTurns || (myTurns==bestMyTurns && (margin>bestMargin || (margin==bestMargin && enemyDist<bestEnemyDist)))){
                    bestMyTurns=myTurns; bestMargin=margin; bestEnemyDist=enemyDist; bestDir=d;
                }
            }
            action = (bestDir==-1)? moveCmds[0] : moveCmds[bestDir];
        } else {
            action = chooseRoleEngageMove(grid, myPos, dirs, moveCmds, threat, myRole);
            if (action == null) {
                // 실패 시 패트롤로 폴백
                action = choosePatrolMove(grid, myPos, dirs, moveCmds, threat);
            }
        }

        // 디버그 출력
        System.out.printf("[T2-TEAM] role=%s action=%s\n", myRole, action);

        return action;
    }

    // ===== 팀 집결 유틸 =====
    private static boolean isInside(String[][] grid, int r, int c){ return r>=0 && r<grid.length && c>=0 && c<grid[0].length; }
    private static int[] computeAllyCentroid(String[][] grid){ int sr=0, sc=0, cnt=0; for(int r=0;r<grid.length;r++){ for(int c=0;c<grid[0].length;c++){ String cell=grid[r][c]; if(cell==null) continue; if(cell.equals("M")||cell.startsWith("M")){ sr+=r; sc+=c; cnt++; } } } if(cnt==0) return null; return new int[]{sr/cnt, sc/cnt}; }
    private static boolean[][] buildRendezvousGoals(String[][] grid, int[] allyCent, int[] enemyPos){ if(allyCent==null) return null; int rows=grid.length, cols=grid[0].length; boolean[][] goals=new boolean[rows][cols]; boolean any=false; for(int r=allyCent[0]-3;r<=allyCent[0]+3;r++){ for(int c=allyCent[1]-3;c<=allyCent[1]+3;c++){ if(!isInside(grid,r,c)) continue; int md=Math.abs(r-allyCent[0])+Math.abs(c-allyCent[1]); if(md<2||md>3) continue; String cell=grid[r][c]; if(cell==null) continue; if(!isWalkable(cell)) continue; if(isBlockedByAllyTurretZone(grid,r,c)) continue; goals[r][c]=true; any=true; } } return any?goals:null; }
    private static int distanceToGoals(int[] start, boolean[][] goals){ int best=Integer.MAX_VALUE; for(int r=0;r<goals.length;r++){ for(int c=0;c<goals[0].length;c++){ if(!goals[r][c]) continue; int d=Math.abs(start[0]-r)+Math.abs(start[1]-c); if(d<best) best=d; } } return best==Integer.MAX_VALUE? -1: best; }

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

    private static int getMyHp(){ String[] me = myAllies.get("M"); return (me!=null&&me.length>=1)? parseIntSafe(me[0]) : 0; }
    private static int[] getMyAmmo(){ String[] me=myAllies.get("M"); int ns=(me!=null&&me.length>=3)? parseIntSafe(me[2]):0; int ms=(me!=null&&me.length>=4)? parseIntSafe(me[3]):0; return new int[]{ns,ms}; }
    private static boolean shouldEngageNow(String[][] grid, int[] myPos){ int[] ammo=getMyAmmo(); if(!(ammo[0]>0||ammo[1]>0)) return false; if(hasLocalSuperiority(grid, myPos, 3)) return true; int myHp=getMyHp(); int[] e=findNearestEnemyToMe(grid,myPos); int dist=(e==null)?Integer.MAX_VALUE: Math.abs(myPos[0]-e[0])+Math.abs(myPos[1]-e[1]); return (dist<=4 && myHp>=40); }

    // ===== 교전 BFS 유틸: 사거리3 직선 LoS 기준 최소 이동 턴 =====
    private static int turnsToShoot(String[][] grid, int[] start, int[] target, int[][] dirs, int maxDepth){ return coreTurnsToShoot(grid,start,target,dirs,maxDepth,false); }
    private static int turnsToShootIgnoreZone(String[][] grid, int[] start, int[] target, int[][] dirs, int maxDepth){ return coreTurnsToShoot(grid,start,target,dirs,maxDepth,true); }
    private static boolean canShoot(String[][] grid, int[] from, int[] to, int[][] dirs){ if(from==null||to==null) return false; for(int d=0; d<4; d++){ int r=from[0], c=from[1]; for(int k=1;k<=3;k++){ r+=dirs[d][0]; c+=dirs[d][1]; if(r<0||r>=grid.length||c<0||c>=grid[0].length) break; String cell=grid[r][c]; if(cell==null) break; if(r==to[0]&&c==to[1]) return true; if("R".equals(cell)||"T".equals(cell)||"F".equals(cell)) break; if("M".equals(cell)||"H".equals(cell)||cell.startsWith("M")||cell.startsWith("E")||"X".equals(cell)) break; } } return false; }
    private static int coreTurnsToShoot(String[][] grid, int[] start, int[] target, int[][] dirs, int maxDepth, boolean ignoreZone){ if(start==null||target==null) return Integer.MAX_VALUE; if(canShoot(grid,start,target,dirs)) return 0; int rows=grid.length, cols=grid[0].length; boolean[][] vis=new boolean[rows][cols]; ArrayDeque<int[]> q=new ArrayDeque<>(); q.offer(new int[]{start[0],start[1],0}); vis[start[0]][start[1]]=true; while(!q.isEmpty()){ int[] cur=q.poll(); int r=cur[0],c=cur[1],d=cur[2]; if(d>=maxDepth) continue; for(int i=0;i<4;i++){ int nr=r+dirs[i][0], nc=c+dirs[i][1]; if(nr<0||nr>=rows||nc<0||nc>=cols) continue; if(vis[nr][nc]) continue; String cell=grid[nr][nc]; if(cell==null) continue; if(!isWalkable(cell)) continue; if(!ignoreZone && isBlockedByAllyTurretZone(grid,nr,nc)) continue; vis[nr][nc]=true; int nd=d+1; int[] np=new int[]{nr,nc}; if(canShoot(grid,np,target,dirs)) return nd; q.offer(new int[]{nr,nc,nd}); } } return Integer.MAX_VALUE; }

    // ===== 방어 위기 판단 및 수비라인 구성/보급 허용 =====
    private static boolean isDefenseEmergency(String[][] grid){ return isDefenseMode(grid) && defenseProactiveFlag; }
    private static boolean canResupplyWhileDefending(String[][] grid){ int md=getMinDistEnemyToH(grid); return md>=6; }
    private static boolean canMoveToSupplyWhileDefending(String[][] grid){ int md=getMinDistEnemyToH(grid); return md>=7; }
    private static java.util.List<int[]> enumerateDefenseRing(String[][] grid, int radius){ java.util.List<int[]> list=new java.util.ArrayList<>(); int[] h=findAllyTurret(grid); if(h==null) return list; int rows=grid.length, cols=grid[0].length; for(int r=h[0]-radius;r<=h[0]+radius;r++){ for(int c=h[1]-radius;c<=h[1]+radius;c++){ if(r<0||r>=rows||c<0||c>=cols) continue; int md=Math.abs(r-h[0])+Math.abs(c-h[1]); if(md!=radius) continue; String cell=grid[r][c]; if(cell==null) continue; if(!isWalkable(cell)) continue; if(isBlockedByAllyTurretZone(grid,r,c)) continue; list.add(new int[]{r,c}); } } return list; }
    private static int scoreDefenseCell(String[][] grid, int[] cell, int[] enemyFocus){ int score=0; if(enemyFocus!=null){ int d=Math.abs(cell[0]-enemyFocus[0])+Math.abs(cell[1]-enemyFocus[1]); score+=Math.max(0,15-d); if(canShoot(grid,cell,enemyFocus,new int[][]{{0,1},{1,0},{0,-1},{-1,0}})) score+=20; } if("S".equals(grid[cell[0]][cell[1]])) score-=2; return score; }
    private static int[] assignDefenseGoalForSelf(String[][] grid){ int[] enemyFocus=findNearestEnemyToH(grid); java.util.List<int[]> ring=enumerateDefenseRing(grid,3); if(ring.isEmpty()) return null; ring.sort((a,b)->Integer.compare(scoreDefenseCell(grid,b,enemyFocus),scoreDefenseCell(grid,a,enemyFocus))); int idx=getClientIndex()%3; if(idx<0) idx=0; if(idx>=ring.size()) idx=ring.size()-1; for(int i=0;i<ring.size();i++){ int at=(idx+i)%ring.size(); int[] g=ring.get(at); if(!isBlockedByAllyTurretZone(grid,g[0],g[1])) return g; } return ring.get(idx); }
    private static String chooseDefenseLineMove(String[][] grid, int[] myPos, int[][] dirs, String[] moveCmds, boolean[][] threat){ int[] goal=assignDefenseGoalForSelf(grid); if(goal==null) return choosePatrolMove(grid,myPos,dirs,moveCmds,threat); boolean[][] goals=new boolean[grid.length][grid[0].length]; goals[goal[0]][goal[1]]=true; int[] hPos=findAllyTurret(grid); Queue<String> path=aStarToGoals(grid,myPos,goals,dirs,moveCmds,threat,hPos); if(path!=null && !path.isEmpty()) return path.poll(); return choosePatrolMove(grid,myPos,dirs,moveCmds,threat); }

    // ===== ORBIT 허용 게이트: 보급 완료 + 전선 형성 + 적 근접 =====
    private static boolean enemyNearby(String[][] grid, int[] myPos){ int d=distanceToNearestEnemy(grid, myPos); return d>=0 && d<=6; }
    private static boolean anyAllyEngaging(String[][] grid){
        int[] enemyPos = findNearestEnemyToMe(grid, TeamSituationAnalyzer.getMyPosition(grid)); if(enemyPos==null) return false;
        int[] a2=TeamSituationAnalyzer.getAllyPosition(grid, "M2"); int[] a3=TeamSituationAnalyzer.getAllyPosition(grid, "M3");
        return (a2!=null && TeamSituationAnalyzer.getDistance(a2, enemyPos)<=3) || (a3!=null && TeamSituationAnalyzer.getDistance(a3, enemyPos)<=3);
    }
    private static boolean shouldOrbitNow(String[][] grid, int[] myPos, int myMega){ return myMega>=2 && enemyNearby(grid,myPos) && anyAllyEngaging(grid); }

    // ===== 축 기반 포위 포지셔닝(사거리 2~3에서 상하좌우 라인 담당) =====
    private static int[] preferredAxisOrder(){
        // 0:R,1:D,2:L,3:U (DIRS 인덱스와 동일)
        int idx = getClientIndex()%3;
        if (idx==0) return new int[]{3,2,0,1}; // U, L, R, D
        if (idx==1) return new int[]{2,0,3,1}; // L, R, U, D
        return new int[]{0,3,2,1};             // R, U, L, D
    }
    private static boolean isSafeOrAcceptable(boolean[][] threat,int r,int c){ return threat==null || !threat[r][c]; }
    private static boolean[][] buildAxisGoals(String[][] grid, int[] enemyPos, int[][] dirs, boolean[][] threat){
        boolean[][] goals = new boolean[grid.length][grid[0].length];
        int[] order = preferredAxisOrder(); boolean any=false;
        for(int oi=0; oi<order.length; oi++){
            int d = order[oi];
            for(int dist=3; dist>=2; dist--){
                int gr = enemyPos[0] + dirs[d][0]*dist;
                int gc = enemyPos[1] + dirs[d][1]*dist;
                if(!isInside(grid,gr,gc)) continue;
                String cell = grid[gr][gc]; if(cell==null) continue; if(!isWalkable(cell)) continue; if(isBlockedByAllyTurretZone(grid,gr,gc)) continue;
                if(!isSafeOrAcceptable(threat,gr,gc)) continue;
                goals[gr][gc] = true; any=true; break; // 한 축당 하나만 선택
            }
        }
        return any? goals : null;
    }
    private static String moveToAxisEncirclement(String[][] grid, int[] myPos, int[][] dirs, String[] moveCmds, boolean[][] threat){
        int[] enemy = findNearestEnemyToMe(grid, myPos); if(enemy==null) return null;
        boolean[][] goals = buildAxisGoals(grid, enemy, dirs, threat); if(goals==null) return null;
        int[] hPos = findAllyTurret(grid);
        Queue<String> path = aStarToGoals(grid, myPos, goals, dirs, moveCmds, threat, hPos);
        if(path==null || path.isEmpty()) return null; return path.poll();
    }

    // ===== 끌어내기 접근(중거리 5..8에서 선제 턴 유리 유지) =====
    private static String chooseTeaseApproachMove(String[][] grid, int[] myPos, int[][] dirs, String[] moveCmds, boolean[][] threat){
        int[] enemy = findNearestEnemyToMe(grid, myPos); if(enemy==null) return null;
        int curD = Math.abs(myPos[0]-enemy[0])+Math.abs(myPos[1]-enemy[1]);
        if (curD < 5 || curD > 8) return null;
        int bestDir=-1; int bestMyTurns=Integer.MAX_VALUE; int bestMargin=Integer.MIN_VALUE; int bestDistBias=Integer.MAX_VALUE; int bestAlignScore=-1;
        for(int d=0; d<4; d++){
            int nr=myPos[0]+dirs[d][0], nc=myPos[1]+dirs[d][1];
            if(nr<0||nr>=grid.length||nc<0||nc>=grid[0].length) continue; String cell=grid[nr][nc];
            if(!isWalkable(cell)) continue; if(isBlockedByAllyTurretZone(grid,nr,nc)) continue;
            if(threat!=null && threat[nr][nc]) continue;
            int nd = Math.abs(nr-enemy[0])+Math.abs(nc-enemy[1]); if (nd<4 || nd>6) continue; // 4~6 선호
            int myT = turnsToShoot(grid, new int[]{nr,nc}, enemy, dirs, 12);
            int enT = turnsToShootIgnoreZone(grid, enemy, new int[]{nr,nc}, dirs, 12);
            int margin = (enT==Integer.MAX_VALUE?50:enT) - (myT==Integer.MAX_VALUE?50:myT);
            // 선제 불리 배제 + 적 즉시 사격 금지
            if (myT > enT) continue;
            if (canShoot(grid, enemy, new int[]{nr,nc}, dirs)) continue;
            // 정렬/각도 선호: 같은 행/열이면서 중간 차폐 없는 경우 가산
            boolean aligned = (nr==enemy[0] || nc==enemy[1]);
            boolean clearLine = aligned && hasClearLine(grid, new int[]{nr,nc}, enemy, dirs);
            int alignScore = clearLine? 2 : (aligned? 1: 0);
            int distBias = Math.abs(nd-4); // 4에 더 가깝게 유지
            if (myT<bestMyTurns || (myT==bestMyTurns && (margin>bestMargin || (margin==bestMargin && (alignScore>bestAlignScore || (alignScore==bestAlignScore && distBias<bestDistBias)))))){
                bestMyTurns=myT; bestMargin=margin; bestDistBias=distBias; bestAlignScore=alignScore; bestDir=d;
            }
        }
        return bestDir==-1? null : moveCmds[bestDir];
    }

    private static boolean hasClearLine(String[][] grid, int[] from, int[] to, int[][] dirs){
        if (from[0]!=to[0] && from[1]!=to[1]) return false;
        int dr = Integer.compare(to[0]-from[0], 0);
        int dc = Integer.compare(to[1]-from[1], 0);
        int r=from[0], c=from[1];
        while(true){
            r+=dr; c+=dc; if(r<0||r>=grid.length||c<0||c>=grid[0].length) return false;
            if (r==to[0] && c==to[1]) return true;
            String cell=grid[r][c]; if(cell==null) return false;
            if ("R".equals(cell) || "T".equals(cell) || "F".equals(cell)) return false;
            if ("M".equals(cell) || "H".equals(cell) || cell.startsWith("M") || cell.startsWith("E") || "X".equals(cell)) return false;
        }
    }
}
