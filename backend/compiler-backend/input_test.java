import java.util.*;

public class input_test {
    public static void main(String[] args) {
        try (Scanner sc = new Scanner(System.in)) {
            // Test multiple string inputs
            System.out.print("Enter your first name: ");
            String firstName = sc.nextLine();
            
            System.out.print("Enter your last name: ");
            String lastName = sc.nextLine();
            
            System.out.print("Enter your age: ");
            int age = sc.nextInt();
            
            System.out.print("Enter your city: ");
            sc.nextLine(); // Consume leftover newline
            String city = sc.nextLine();
            
            System.out.print("Enter your profession: ");
            String profession = sc.nextLine();
            
            // Display all inputs
            System.out.println("\n=== User Information ===");
            System.out.println("Name: " + firstName + " " + lastName);
            System.out.println("Age: " + age);
            System.out.println("City: " + city);
            System.out.println("Profession: " + profession);
            
            // Test integer operations
            System.out.print("\nEnter first number: ");
            int a = sc.nextInt();
            System.out.print("Enter second number: ");
            int b = sc.nextInt();
            int sum = a + b;
            System.out.println("Sum: " + sum);
            
            // Test conditional
            if (sum > 50) {
                System.out.println("That's a large sum!");
            } else {
                System.out.println("That's a moderate sum.");
            }
            
        } catch (NoSuchElementException e) {
            System.out.println("⚠ No input provided!");
        }
    }
}
