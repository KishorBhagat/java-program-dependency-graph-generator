public class Main {      
    public static void main(String[] args) {
        int age = 65;                    
        double price = 100.0;            
        double discount;                 
        if (age >= 60) {                 
            discount = price * 0.2;      // Senior discount (20%)
        } else                         
            discount = price * 0.1;      // Regular discount (10%)
        double finalPrice = price - discount;  
        System.out.println("Final price: " + finalPrice);  
    }
}