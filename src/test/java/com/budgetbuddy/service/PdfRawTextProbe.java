package com.budgetbuddy.service;

import java.io.File;
import java.io.FileInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Dump raw text from a PDF so we can inspect what's actually available
 * for extractors to work with. Drives off {@code -Dpdf.raw.file=<path>}.
 */
@EnabledIfSystemProperty(named = "pdf.raw.file", matches = ".+")
class PdfRawTextProbe {

    @Test
    void dump() throws Exception {
        final String path = System.getProperty("pdf.raw.file");
        final File pdf = new File(path);
        try (PDDocument doc = Loader.loadPDF(new RandomAccessReadBufferedFile(pdf))) {
            final PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            final String text = stripper.getText(doc);
            System.out.println();
            System.out.println("======== RAW TEXT [" + pdf.getName() + "] ========");
            System.out.println(text);
            System.out.println("======== END ========");
        }
    }
}
