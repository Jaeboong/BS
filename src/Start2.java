public class Start2 {
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
				MTank1.main(new String[]{});
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
				MTank2.main(new String[]{});
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
				MTank3.main(new String[]{});
			}
		});
		t6.start();
	}
}


