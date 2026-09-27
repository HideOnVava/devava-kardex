module com.devavaxp.kardex {
    requires javafx.controls;
    requires javafx.swing;     // SwingFXUtils: exportar imagen
    requires java.desktop;     // ImageIO y la carpeta Documentos de Windows (FileSystemView)
    requires com.google.gson;
    requires org.jsoup;

    // JavaFX crea la Application por reflexión; Gson lee y escribe los datos del kardex.
    opens com.devavaxp.kardex to javafx.graphics, com.google.gson;
}
