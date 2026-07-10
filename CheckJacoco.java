import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class CheckJacoco {
    public static void main(String[] args) throws IOException {
        String file = "C:\\Users\\SSAFY\\ssafy\\multi-currency-ledger-service\\build\\reports\\jacoco\\test\\jacocoTestReport.csv";
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            String header = br.readLine();
            int bMissedIdx = 5; // BRANCH_MISSED
            int bCovIdx = 6; // BRANCH_COVERED
            int classIdx = 2; // CLASS
            int pkgIdx = 1; // PACKAGE
            
            while ((line = br.readLine()) != null) {
                String[] cols = line.split(",");
                if (cols.length > 6) {
                    int missed = Integer.parseInt(cols[bMissedIdx]);
                    int covered = Integer.parseInt(cols[bCovIdx]);
                    String cls = cols[classIdx];
                    String pkg = cols[pkgIdx];
                    
                    if (missed > 0 && !cls.contains("Exception") && !cls.contains("Config") 
                            && !cls.contains("Dto") && !cls.contains("Application") 
                            && !cls.contains("Entity") && !cls.contains("Event") 
                            && !cls.contains("Result") && !pkg.contains("common")) {
                        System.out.println(pkg + "." + cls + " -> Missed Branches: " + missed + ", Covered: " + covered);
                    }
                }
            }
        }
    }
}
