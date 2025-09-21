public class Start3 {
	public static void main(String[] args) throws InterruptedException {
		Thread t1 = new Thread(new Runnable() {
			public void run() {
				Tank1.main(new String[]{});
			}
		});
		t1.start();
		Thread.sleep(300);

		Thread t2 = new Thread(new Runnable() {
			public void run() {
				try {
					KTank1.main(new String[]{});
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		});
		t2.start();
		Thread.sleep(300);

		Thread t3 = new Thread(new Runnable() {
			public void run() {
				Tank2.main(new String[]{});
			}
		});
		t3.start();
		Thread.sleep(300);

		Thread t4 = new Thread(new Runnable() {
			public void run() {
				try {
					KTank2.main(new String[]{});
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		});
		t4.start();
		Thread.sleep(300);

		Thread t5 = new Thread(new Runnable() {
			public void run() {
				Tank3.main(new String[]{});
			}
		});
		t5.start();
		Thread.sleep(300);

		Thread t6 = new Thread(new Runnable() {
			public void run() {
				try {
					KTank3.main(new String[]{});
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		});
		t6.start();
	}
}


