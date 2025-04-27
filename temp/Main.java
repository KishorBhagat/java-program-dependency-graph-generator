public class Main {       // Node 1
    public static void main(String[] args) { // Node 2
        int age = 65;                   // Node 3
        double price = 100.0;           // Node 4
        double discount;                // Node 5
        if (age >= 60) {                // Node 6
            discount = price * 0.2;     // Node 7: Senior discount (20%)
        } else                        // Node 8
            discount = price * 0.1;     // Node 9: Regular discount (10%)
        double finalPrice = price - discount; // Node 10
        System.out.println("Final price: " + finalPrice); // Node 11 (criterion)
    }
}