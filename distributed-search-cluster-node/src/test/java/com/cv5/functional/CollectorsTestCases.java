/*
 *  MIT License
 *
 *  Copyright (c) 2019 Michael Pogrebinsky - Distributed Systems & Cloud Computing with Java
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package com.cv5.functional;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

public class CollectorsTestCases {
    
    // Sample data classes
    static class Employee {
        String name, department;
        int age;
        double salary;
        
        Employee(String name, String dept, int age, double salary) {
            this.name = name; this.department = dept; 
            this.age = age; this.salary = salary;
        }
        
        // getters
        public String getName() { return name; }
        public String getDepartment() { return department; }
        public int getAge() { return age; }
        public double getSalary() { return salary; }
        
        @Override
        public String toString() {
            return name + "(" + department + ", $" + salary + ")";
        }
    }
    
    static class Product {
        String name, category;
        double price;
        boolean inStock;
        
        Product(String name, String category, double price, boolean inStock) {
            this.name = name; this.category = category; 
            this.price = price; this.inStock = inStock;
        }
        
        // getters
        public String getName() { return name; }
        public String getCategory() { return category; }
        public double getPrice() { return price; }
        public boolean isInStock() { return inStock; }
        
        @Override
        public String toString() {
            return name + "($" + price + ", " + (inStock ? "In Stock" : "Out of Stock") + ")";
        }
    }
    
    // Test data
    List<Employee> employees = Arrays.asList(
        new Employee("Alice", "Engineering", 28, 85000),
        new Employee("Bob", "Sales", 35, 65000),
        new Employee("Charlie", "Engineering", 42, 95000),
        new Employee("Diana", "HR", 31, 55000),
        new Employee("Eve", "Sales", 29, 70000),
        new Employee("Frank", "Engineering", 38, 90000)
    );
    
    List<Product> products = Arrays.asList(
        new Product("Laptop", "Electronics", 1200.0, true),
        new Product("Mouse", "Electronics", 25.0, false),
        new Product("Book", "Education", 15.0, true),
        new Product("Tablet", "Electronics", 400.0, true),
        new Product("Pen", "Office", 2.0, false),
        new Product("Monitor", "Electronics", 300.0, true)
    );

    // =================================================================
    // TERMINAL COLLECTION OPERATIONS
    // =================================================================
    
    @Test
    void testToList() {
        // Basic collection
        List<String> names = employees.stream()
            .map(Employee::getName)
            .collect(Collectors.toList());
        assertEquals(6, names.size());
        assertTrue(names.contains("Alice"));
        
        // Filtered collection
        List<Employee> highEarners = employees.stream()
            .filter(e -> e.getSalary() > 80000)
            .collect(Collectors.toList());
        assertEquals(3, highEarners.size());
        
        // Empty stream case
        List<String> empty = employees.stream()
            .filter(e -> e.getAge() > 100)
            .map(Employee::getName)
            .collect(Collectors.toList());
        assertTrue(empty.isEmpty());
    }
    
    @Test
    void testToSet() {
        // Duplicate removal
        Set<String> departments = employees.stream()
            .map(Employee::getDepartment)
            .collect(Collectors.toSet());
        assertEquals(3, departments.size()); // Engineering, Sales, HR
        assertTrue(departments.contains("Engineering"));
        
        // Case with duplicates in source
        List<String> duplicateList = Arrays.asList("A", "B", "A", "C", "B", "A");
        Set<String> uniqueSet = duplicateList.stream()
            .collect(Collectors.toSet());
        assertEquals(3, uniqueSet.size());
    }
    
    @Test
    void testToMap() {
        // Simple key-value mapping
        Map<String, Double> salaryMap = employees.stream()
            .collect(Collectors.toMap(Employee::getName, Employee::getSalary));
        assertEquals(85000.0, salaryMap.get("Alice"));
        
        // With duplicate key resolution
        Map<String, Employee> deptToHighestPaid = employees.stream()
            .collect(Collectors.toMap(
                Employee::getDepartment,
                emp -> emp,
                (emp1, emp2) -> emp1.getSalary() > emp2.getSalary() ? emp1 : emp2
            ));
        assertEquals("Charlie", deptToHighestPaid.get("Engineering").getName());
        
        // To specific map type
        Map<String, String> linkedMap = employees.stream()
            .collect(Collectors.toMap(
                Employee::getName,
                Employee::getDepartment,
                (v1, v2) -> v1,
                LinkedHashMap::new
            ));
        assertTrue(linkedMap instanceof LinkedHashMap);
    }
    
    @Test
    void testToCollection() {
        // To TreeSet (sorted)
        TreeSet<String> sortedNames = employees.stream()
            .map(Employee::getName)
            .collect(Collectors.toCollection(TreeSet::new));
        assertEquals("Alice", sortedNames.first());
        
        // To ArrayDeque
        ArrayDeque<Employee> queue = employees.stream()
            .filter(e -> e.getSalary() > 70000)
            .collect(Collectors.toCollection(ArrayDeque::new));
        assertFalse(queue.isEmpty());
    }

    // =================================================================
    // STRING OPERATIONS
    // =================================================================
    
    @Test
    void testJoining() {
        // Simple joining
        String allNames = employees.stream()
            .map(Employee::getName)
            .collect(Collectors.joining(", "));
        assertTrue(allNames.contains("Alice, Bob"));
        
        // With prefix and suffix
        String formattedNames = employees.stream()
            .filter(e -> e.getDepartment().equals("Engineering"))
            .map(Employee::getName)
            .collect(Collectors.joining(", ", "Engineers: [", "]"));
        assertTrue(formattedNames.startsWith("Engineers: ["));
        assertTrue(formattedNames.endsWith("]"));
        
        // Edge cases
        String emptyJoin = employees.stream()
            .filter(e -> e.getAge() > 100)
            .map(Employee::getName)
            .collect(Collectors.joining(", ", "[", "]"));
        assertEquals("[]", emptyJoin);
        
        String singleJoin = Arrays.asList("OnlyOne").stream()
            .collect(Collectors.joining(", ", "[", "]"));
        assertEquals("[OnlyOne]", singleJoin);
    }

    // =================================================================
    // MATHEMATICAL OPERATIONS
    // =================================================================
    
    @Test
    void testCounting() {
        // Basic counting
        long totalEmployees = employees.stream()
            .collect(Collectors.counting());
        assertEquals(6L, totalEmployees);
        
        // Conditional counting
        long engineeringCount = employees.stream()
            .filter(e -> e.getDepartment().equals("Engineering"))
            .collect(Collectors.counting());
        assertEquals(3L, engineeringCount);
        
        // Empty stream
        long zeroCount = employees.stream()
            .filter(e -> e.getAge() > 100)
            .collect(Collectors.counting());
        assertEquals(0L, zeroCount);
    }
    
    @Test
    void testSumming() {
        // Sum of integers
        int totalAge = employees.stream()
            .collect(Collectors.summingInt(Employee::getAge));
        assertEquals(203, totalAge); // 28+35+42+31+29+38
        
        // Sum of doubles
        double totalSalary = employees.stream()
            .collect(Collectors.summingDouble(Employee::getSalary));
        assertEquals(460000.0, totalSalary);
        
        // Sum of longs
        long productPricesInCents = products.stream()
            .collect(Collectors.summingLong(p -> (long)(p.getPrice() * 100)));
        assertEquals(194200L, productPricesInCents);
        
        // Edge case - empty stream
        double zeroSum = employees.stream()
            .filter(e -> e.getAge() > 100)
            .collect(Collectors.summingDouble(Employee::getSalary));
        assertEquals(0.0, zeroSum);
    }
    
    @Test
    void testAveraging() {
        // Average of integers
        double avgAge = employees.stream()
            .collect(Collectors.averagingInt(Employee::getAge));
        assertEquals(33.833333, avgAge, 0.001);
        
        // Average of doubles
        double avgSalary = employees.stream()
            .collect(Collectors.averagingDouble(Employee::getSalary));
        assertEquals(76666.67, avgSalary, 0.01);
        
        // Average with filtering
        double avgEngineerSalary = employees.stream()
            .filter(e -> e.getDepartment().equals("Engineering"))
            .collect(Collectors.averagingDouble(Employee::getSalary));
        assertEquals(90000.0, avgEngineerSalary, 0.01);
    }
    
    @Test
    void testMinMax() {
        // Finding minimum
        Optional<Employee> youngest = employees.stream()
            .collect(Collectors.minBy(Comparator.comparing(Employee::getAge)));
        assertTrue(youngest.isPresent());
        assertEquals("Alice", youngest.get().getName());
        
        // Finding maximum
        Optional<Employee> oldest = employees.stream()
            .collect(Collectors.maxBy(Comparator.comparing(Employee::getAge)));
        assertTrue(oldest.isPresent());
        assertEquals("Charlie", oldest.get().getName());
        
        // Complex comparator
        Optional<Employee> highestPaidInHR = employees.stream()
            .filter(e -> e.getDepartment().equals("HR"))
            .collect(Collectors.maxBy(Comparator.comparing(Employee::getSalary)));
        assertTrue(highestPaidInHR.isPresent());
        assertEquals("Diana", highestPaidInHR.get().getName());
        
        // Empty stream case
        Optional<Employee> noResult = employees.stream()
            .filter(e -> e.getAge() > 100)
            .collect(Collectors.minBy(Comparator.comparing(Employee::getAge)));
        assertFalse(noResult.isPresent());
    }
    
    @Test
    void testSummarizingStatistics() {
        // Integer statistics
        IntSummaryStatistics ageStats = employees.stream()
            .collect(Collectors.summarizingInt(Employee::getAge));
        assertEquals(6, ageStats.getCount());
        assertEquals(28, ageStats.getMin());
        assertEquals(42, ageStats.getMax());
        assertEquals(203, ageStats.getSum());
        assertEquals(33.833333, ageStats.getAverage(), 0.001);
        
        // Double statistics
        DoubleSummaryStatistics salaryStats = employees.stream()
            .collect(Collectors.summarizingDouble(Employee::getSalary));
        assertEquals(6, salaryStats.getCount());
        assertEquals(55000.0, salaryStats.getMin());
        assertEquals(95000.0, salaryStats.getMax());
        
        // Filtered statistics
        DoubleSummaryStatistics engineerStats = employees.stream()
            .filter(e -> e.getDepartment().equals("Engineering"))
            .collect(Collectors.summarizingDouble(Employee::getSalary));
        assertEquals(3, engineerStats.getCount());
        assertEquals(270000.0, engineerStats.getSum());
    }

    // =================================================================
    // REDUCTION OPERATIONS
    // =================================================================
    
    @Test
    void testReducing() {
        // Sum using reducing
        Optional<Double> totalSalary = employees.stream()
            .map(Employee::getSalary)
            .collect(Collectors.reducing(Double::sum));
        assertTrue(totalSalary.isPresent());
        assertEquals(460000.0, totalSalary.get());
        
        // Reducing with identity
        String allDepartments = employees.stream()
            .map(Employee::getDepartment)
            .collect(Collectors.reducing("Departments: ", (a, b) -> a + b + " "));
        assertTrue(allDepartments.startsWith("Departments: "));
        
        // Complex reduction - finding employee with longest name
        Optional<Employee> longestName = employees.stream()
            .collect(Collectors.reducing((e1, e2) -> 
                e1.getName().length() >= e2.getName().length() ? e1 : e2));
        assertTrue(longestName.isPresent());
        assertEquals("Charlie", longestName.get().getName());
        
        // Reduction with mapper
//        Optional<Integer> totalNameLength = employees.stream()
        var totalNameLength = employees.stream()
            .collect(Collectors.reducing(0,
                e -> e.getName().length(), 
                Integer::sum));
        assertTrue(totalNameLength >= 0);
        assertEquals(32, totalNameLength); // Alice(5)+Bob(3)+Charlie(7)+Diana(5)+Eve(3)+Frank(5)
    }

    // =================================================================
    // GROUPING OPERATIONS
    // =================================================================
    
    @Test
    void testGroupingBy() {
        // Simple grouping
        Map<String, List<Employee>> byDepartment = employees.stream()
            .collect(Collectors.groupingBy(Employee::getDepartment));
        assertEquals(3, byDepartment.size());
        assertEquals(3, byDepartment.get("Engineering").size());
        
        // Grouping with downstream collector - counting
        Map<String, Long> deptCounts = employees.stream()
            .collect(Collectors.groupingBy(
                Employee::getDepartment, 
                Collectors.counting()
            ));
        assertEquals(Long.valueOf(3), deptCounts.get("Engineering"));
        assertEquals(Long.valueOf(2), deptCounts.get("Sales"));
        
        // Grouping with averaging
        Map<String, Double> avgSalaryByDept = employees.stream()
            .collect(Collectors.groupingBy(
                Employee::getDepartment,
                Collectors.averagingDouble(Employee::getSalary)
            ));
        assertEquals(90000.0, avgSalaryByDept.get("Engineering"), 0.01);
        
        // Multi-level grouping
        Map<String, Map<String, List<Employee>>> complexGrouping = employees.stream()
            .collect(Collectors.groupingBy(
                Employee::getDepartment,
                Collectors.groupingBy(e -> e.getAge() < 35 ? "Young" : "Senior")
            ));
        assertTrue(complexGrouping.get("Engineering").containsKey("Young"));
        assertTrue(complexGrouping.get("Engineering").containsKey("Senior"));
        
        // Grouping to specific map type
        TreeMap<String, List<Employee>> sortedGrouping = employees.stream()
            .collect(Collectors.groupingBy(
                Employee::getDepartment,
                TreeMap::new,
                Collectors.toList()
            ));
        assertTrue(sortedGrouping instanceof TreeMap);
        
        // Grouping with complex downstream - top earner per department
        Map<String, Optional<Employee>> topEarnerByDept = employees.stream()
            .collect(Collectors.groupingBy(
                Employee::getDepartment,
                Collectors.maxBy(Comparator.comparing(Employee::getSalary))
            ));
        assertEquals("Charlie", topEarnerByDept.get("Engineering").get().getName());
    }
    
    @Test
    void testGroupingByConcurrent() {
        // Concurrent grouping for parallel streams
        ConcurrentMap<String, List<Employee>> concurrentGrouping = employees.parallelStream()
            .collect(Collectors.groupingByConcurrent(Employee::getDepartment));
        assertEquals(3, concurrentGrouping.size());
        assertTrue(concurrentGrouping instanceof ConcurrentMap);
        
        // With downstream collector
        ConcurrentMap<String, Long> concurrentCounting = employees.parallelStream()
            .collect(Collectors.groupingByConcurrent(
                Employee::getDepartment,
                Collectors.counting()
            ));
        assertEquals(Long.valueOf(3), concurrentCounting.get("Engineering"));
    }

    // =================================================================
    // PARTITIONING OPERATIONS
    // =================================================================
    
    @Test
    void testPartitioningBy() {
        // Simple partitioning - high vs low earners
        Map<Boolean, List<Employee>> highLowEarners = employees.stream()
            .collect(Collectors.partitioningBy(e -> e.getSalary() > 70000));
        assertEquals(4, highLowEarners.get(true).size());  // High earners
        assertEquals(2, highLowEarners.get(false).size()); // Low earners
        
        // Partitioning with downstream collector - counting
        Map<Boolean, Long> seniorCounts = employees.stream()
            .collect(Collectors.partitioningBy(
                e -> e.getAge() >= 35,
                Collectors.counting()
            ));
        assertEquals(Long.valueOf(3), seniorCounts.get(true));  // Senior
        assertEquals(Long.valueOf(3), seniorCounts.get(false)); // Junior
        
        // Partitioning with averaging
        Map<Boolean, Double> avgSalaryByExperience = employees.stream()
            .collect(Collectors.partitioningBy(
                e -> e.getAge() >= 35,
                Collectors.averagingDouble(Employee::getSalary)
            ));
        assertTrue(avgSalaryByExperience.get(true) > avgSalaryByExperience.get(false));
        
        // Complex partitioning - products in stock with statistics
        Map<Boolean, DoubleSummaryStatistics> stockStats = products.stream()
            .collect(Collectors.partitioningBy(
                Product::isInStock,
                Collectors.summarizingDouble(Product::getPrice)
            ));
        assertTrue(stockStats.get(true).getCount() > 0);
        assertTrue(stockStats.get(false).getCount() > 0);
    }

    // =================================================================
    // MAPPING OPERATIONS
    // =================================================================
    
    @Test
    void testMapping() {
        // Collect names grouped by department
        Map<String, List<String>> namesByDept = employees.stream()
            .collect(Collectors.groupingBy(
                Employee::getDepartment,
                Collectors.mapping(Employee::getName, Collectors.toList())
            ));
        assertTrue(namesByDept.get("Engineering").contains("Alice"));
        
        // Collect unique departments of high earners
        Set<String> highEarnerDepts = employees.stream()
            .filter(e -> e.getSalary() > 70000)
            .collect(Collectors.mapping(Employee::getDepartment, Collectors.toSet()));
        assertEquals(2, highEarnerDepts.size()); // Engineering and Sales
        
        // Complex mapping - collect salary ranges by department
        Map<String, String> salaryRangesByDept = employees.stream()
            .collect(Collectors.groupingBy(
                Employee::getDepartment,
                Collectors.mapping(
                    e -> "$" + e.getSalary(),
                    Collectors.joining(", ", "[", "]")
                )
            ));
        assertTrue(salaryRangesByDept.get("Engineering").contains("$85000"));
    }
    
    @Test
    void testFlatMapping() {
        // Flatten department names to individual characters
        List<Employee> testEmployees = Arrays.asList(
            new Employee("John", "IT", 30, 50000),
            new Employee("Jane", "HR", 25, 45000)
        );
        
        Set<Character> allDeptChars = testEmployees.stream()
            .collect(Collectors.flatMapping(
                emp -> emp.getDepartment().chars()
                    .mapToObj(c -> (char) c),
                Collectors.toSet()
            ));
        assertTrue(allDeptChars.contains('I'));
        assertTrue(allDeptChars.contains('T'));
        assertTrue(allDeptChars.contains('H'));
        assertTrue(allDeptChars.contains('R'));
    }
    
    @Test
    void testFiltering() {
        // Filter during collection - only high earners by department
        Map<String, List<Employee>> highEarnersByDept = employees.stream()
            .collect(Collectors.groupingBy(
                Employee::getDepartment, // employee -> employee.getDepartment()
                Collectors.filtering(
                    e -> e.getSalary() > 70000,
                    Collectors.toList()
                )
            ));
        
        // Sales department should have 1 high earner (Eve)
        assertEquals(3, highEarnersByDept.get("Engineering").size());
        assertEquals("Alice", highEarnersByDept.get("Engineering").get(0).getName());
        
        // HR department should have no high earners
        assertTrue(highEarnersByDept.get("HR").isEmpty());
        
        // Combined with other collectors
        Map<String, Long> highEarnerCountsByDept = employees.stream()
            .collect(Collectors.groupingBy(
                Employee::getDepartment,
                Collectors.filtering(
                    e -> e.getSalary() > 80000,
                    Collectors.counting()
                )
            ));
        assertEquals(Long.valueOf(3), highEarnerCountsByDept.get("Engineering"));
        assertEquals(Long.valueOf(0), highEarnerCountsByDept.get("Sales"));
    }

    // =================================================================
    // TEEING (Java 12+)
    // =================================================================
    
    @Test
    void testTeeing() {
        // Collect both min and max salary in one pass
        record MinMax(double min, double max) {}
        
        MinMax salaryRange = employees.stream()
            .collect(Collectors.teeing(
                Collectors.minBy(Comparator.comparing(Employee::getSalary)),
                Collectors.maxBy(Comparator.comparing(Employee::getSalary)),
                (min, max) -> new MinMax(
                    min.map(Employee::getSalary).orElse(0.0),
                    max.map(Employee::getSalary).orElse(0.0)
                )
            ));
        assertEquals(55000.0, salaryRange.min());
        assertEquals(95000.0, salaryRange.max());
        
        // Count and average in one pass
        record CountAverage(long count, double average) {}
        
        CountAverage stats = employees.stream()
            .filter(e -> e.getDepartment().equals("Engineering"))
            .collect(Collectors.teeing(
                Collectors.counting(),
                Collectors.averagingDouble(Employee::getSalary),
                CountAverage::new
            ));
        assertEquals(3L, stats.count());
        assertEquals(90000.0, stats.average(), 0.01);
    }

    // =================================================================
    // EDGE CASES AND SPECIAL SCENARIOS
    // =================================================================
    
    @Test
    void testEdgeCases() {
        // Empty stream cases
        List<Employee> emptyList = employees.stream()
            .filter(e -> e.getAge() > 100)
            .collect(Collectors.toList());
        assertTrue(emptyList.isEmpty());
        
        Map<String, List<Employee>> emptyGrouping = employees.stream()
            .filter(e -> e.getAge() > 100)
            .collect(Collectors.groupingBy(Employee::getDepartment));
        assertTrue(emptyGrouping.isEmpty());
        
        // Null handling (be careful with nulls in streams)
        List<String> names = Arrays.asList("Alice", null, "Bob", null, "Charlie");
        Map<Boolean, List<String>> nullPartition = names.stream()
            .collect(Collectors.partitioningBy(Objects::isNull));
        assertEquals(3, nullPartition.get(false).size()); // Non-null names
        assertEquals(2, nullPartition.get(true).size());  // Null names
        
        // Single element cases
        List<Employee> singleEmployee = Arrays.asList(employees.get(0));
        Map<String, List<Employee>> singleGrouping = singleEmployee.stream()
            .collect(Collectors.groupingBy(Employee::getDepartment));
        assertEquals(1, singleGrouping.size());
        assertEquals(1, singleGrouping.get("Engineering").size());
        
        // Large numbers
        List<Integer> largeNumbers = IntStream.range(0, 1000000)
            .boxed()
            .collect(Collectors.toList());
        assertEquals(1000000, largeNumbers.size());
        
        long evenCount = largeNumbers.stream()
            .collect(Collectors.partitioningBy(n -> n % 2 == 0, Collectors.counting()))
            .get(true);
        assertEquals(500000L, evenCount);
    }
    
    @Test
    void testComplexRealWorldScenarios() {
        // Scenario: Department analysis report
        record DepartmentReport(
            String department,
            long employeeCount,
            double avgSalary,
            String highestPaidEmployee,
            double totalSalaryBudget
        ) {}
        
        Map<String, DepartmentReport> deptReports = employees.stream()
            .collect(Collectors.groupingBy(
                Employee::getDepartment,
                Collectors.teeing(
                    Collectors.teeing(
                        Collectors.counting(),
                        Collectors.averagingDouble(Employee::getSalary),
                        (count, avg) -> new Object[]{count, avg}
                    ),
                    Collectors.teeing(
                        Collectors.maxBy(Comparator.comparing(Employee::getSalary)),
                        Collectors.summingDouble(Employee::getSalary),
                        (maxEmp, totalSalary) -> new Object[]{maxEmp, totalSalary}
                    ),
                    (first, second) -> {
                        Object[] firstArray = (Object[]) first;
                        Object[] secondArray = (Object[]) second;
                        return new DepartmentReport(
                            "", // Will be set by outer collector
                            (Long) firstArray[0],
                            (Double) firstArray[1],
                            ((Optional<Employee>) secondArray[0]).map(Employee::getName).orElse("None"),
                            (Double) secondArray[1]
                        );
                    }
                )
            ));
        
        // Verify Engineering department report
        DepartmentReport engReport = deptReports.get("Engineering");
        assertEquals(3L, engReport.employeeCount());
        assertEquals(90000.0, engReport.avgSalary(), 0.01);
        assertEquals("Charlie", engReport.highestPaidEmployee());
        assertEquals(270000.0, engReport.totalSalaryBudget(), 0.01);
        
        // Scenario: Product inventory analysis
        Map<String, Map<Boolean, List<Product>>> inventoryAnalysis = products.stream()
            .collect(Collectors.groupingBy(
                Product::getCategory,
                Collectors.partitioningBy(Product::isInStock)
            ));
        
        // Electronics category should have both in-stock and out-of-stock items
        assertTrue(inventoryAnalysis.get("Electronics").get(true).size() > 0);  // In stock
        assertTrue(inventoryAnalysis.get("Electronics").get(false).size() > 0); // Out of stock
    }
}