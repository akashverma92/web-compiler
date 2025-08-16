import java.util.*;

public class test_compiler {
    public static void main(String[] args) {
        try (Scanner sc = new Scanner(System.in)) {
            // Test System.out.print() without newline
            System.out.print("Testing System.out.print(): ");
            System.out.println("SUCCESS!");
            
            // Test integer operations
            System.out.print("Enter first number: ");
            int a = sc.nextInt();
            System.out.print("Enter second number: ");
            int b = sc.nextInt();
            System.out.print("Sum: ");
            System.out.println(a + b);
            
            // Test loop with System.out.print()
            System.out.print("Enter loop count: ");
            int n = sc.nextInt();
            System.out.print("Loop output: ");
            for (int i = 1; i <= n; i++) {
                System.out.print(i + " ");
            }
            System.out.println();
            
            // Test string input
            System.out.print("Enter your name: ");
            sc.nextLine(); // consume leftover newline
            String name = sc.nextLine();
            System.out.print("Hello, ");
            System.out.println(name + "!");
            
        } catch (NoSuchElementException e) {
            System.out.println("⚠ No input provided!");
        }
    }
}
