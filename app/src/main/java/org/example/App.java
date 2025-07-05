package org.example;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class App {
    public static void main(String[] args) throws Exception {
        File input = new File("example.cql");
        
        if (!input.exists()) {
            System.err.println("CQL file not found: " + input.getAbsolutePath());
            System.err.println("Creating example.cql file for you to test with.");
            createExampleFile();
            System.out.println("Now run the program again.");
            return;
        }
        
        System.out.println("Attempting to translate CQL to ELM...");
        
        try {
            // Use reflection to create the necessary objects since we can't import them directly
            
            // Create ModelManager
            Class<?> modelManagerClass = Class.forName("org.cqframework.cql.cql2elm.ModelManager");
            Object modelManager = modelManagerClass.getDeclaredConstructor().newInstance();
            
            // Create LibraryManager
            Class<?> libraryManagerClass = Class.forName("org.cqframework.cql.cql2elm.LibraryManager");
            Object libraryManager = libraryManagerClass.getDeclaredConstructor(modelManagerClass).newInstance(modelManager);
            
            // Create CqlTranslator using fromFile
            Class<?> translatorClass = Class.forName("org.cqframework.cql.cql2elm.CqlTranslator");
            java.lang.reflect.Method fromFileMethod = translatorClass.getMethod("fromFile", File.class, libraryManagerClass);
            Object translator = fromFileMethod.invoke(null, input, libraryManager);
            
            // Check for errors
            java.lang.reflect.Method getErrorsMethod = translatorClass.getMethod("getErrors");
            java.util.List<?> errors = (java.util.List<?>) getErrorsMethod.invoke(translator);
            
            if (errors.size() > 0) {
                System.err.println("Translation failed due to errors:");
                for (Object error : errors) {
                    System.err.println("  " + error.toString());
                }
                return;
            }
            
            // Get XML output
            java.lang.reflect.Method toXmlMethod = translatorClass.getMethod("toXml");
            String xml = (String) toXmlMethod.invoke(translator);
            
            // Write to output file
            File outputFile = new File("output.xml");
            try (FileWriter writer = new FileWriter(outputFile)) {
                writer.write(xml);
            }
            
            System.out.println("Translation successful!");
            System.out.println("Input: " + input.getAbsolutePath());
            System.out.println("Output: " + outputFile.getAbsolutePath());
            System.out.println("Generated " + xml.length() + " characters of ELM XML");
            
        } catch (Exception e) {
            System.err.println("Error during translation: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void createExampleFile() {
        try {
            File exampleFile = new File("example.cql");
            try (FileWriter writer = new FileWriter(exampleFile)) {
                writer.write("library Test\n\ndefine Hello: 'World'\ndefine AddNumbers: 5 + 3\n");
            }
            System.out.println("Created example.cql file for you to test with.");
        } catch (IOException e) {
            System.err.println("Could not create example file: " + e.getMessage());
        }
    }
}