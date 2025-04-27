# Run the project locally

1.  **Download or clone the repository to your local.**

2.  **Navigate to the** `src` **folder:**
    ```bash
    cd src
    ```

3.  **Compile:**
    ```bash
    javac -cp "../lib/javaparser-core-3.26.4.jar:../lib/json-20230227.jar" DependencyGraph.java
    ```
    **For Windows:**
    ```bash
    javac -cp "../lib/javaparser-core-3.26.4.jar;../lib/json-20230227.jar" DependencyGraph.java
    ```

4.  **In the `temp` folder put your target `.java` file for which dependency graph has to be generated.** (e.g., `Main.java`).

5.  **Run:**
    ```bash
    java -cp ".;../lib/javaparser-core-3.26.4.jar:../lib/json-20230227.jar" DependencyGraph <YourTargetJavaFile>
    ```
    **For Windows:**
    ```bash
    java -cp ".;../lib/javaparser-core-3.26.4.jar;../lib/json-20230227.jar" DependencyGraph <YourTargetJavaFile>
    ```
    * Replace `<YourTargetJavaFile>` with your file name without any `.java`  extension.
    * A `dependencies.json` file will be created in the `temp` folder.
**Make sure:** Your `<YourTargetJavaFile>.java` file is in the `temp` folder in project's root directory.