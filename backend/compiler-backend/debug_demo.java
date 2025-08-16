import java.util.*;

public class debug_demo {
    public static void main(String[] args) {
        try (Scanner sc = new Scanner(System.in)) {
            // Variable declarations and initialization
            System.out.print("Enter your name: ");
            String name = sc.nextLine();
            System.out.println("Hello, " + name + "!");
            
            // Integer operations
            System.out.print("Enter first number: ");
            int a = sc.nextInt();
            System.out.print("Enter second number: ");
            int b = sc.nextInt();
            int sum = a + b;
            System.out.println("Sum: " + sum);
            
            // Conditional statement
            if (sum > 10) {
                System.out.println("Sum is greater than 10!");
            } else {
                System.out.println("Sum is 10 or less.");
            }
            
            // Loop demonstration
            System.out.print("Enter loop count: ");
            int n = sc.nextInt();
            System.out.print("Loop output: ");
            for (int i = 1; i <= n; i++) {
                System.out.print(i + " ");
            }
            System.out.println();
            
            // Array demonstration
            int[] numbers = new int[5];
            System.out.println("Enter 5 numbers:");
            for (int i = 0; i < 5; i++) {
                System.out.print("Number " + (i + 1) + ": ");
                numbers[i] = sc.nextInt();
            }
            
            System.out.print("Array elements: ");
            for (int num : numbers) {
                System.out.print(num + " ");
            }
            System.out.println();
            
            // String operations
            String message = "Programming is fun!";
            System.out.println("Original message: " + message);
            System.out.println("Length: " + message.length());
            System.out.println("Uppercase: " + message.toUpperCase());
            
        } catch (NoSuchElementException e) {
            System.out.println("⚠ No input provided!");
        }
    }
}
