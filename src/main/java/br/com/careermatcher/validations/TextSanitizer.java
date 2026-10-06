package br.com.careermatcher.validations;

import org.owasp.html.*;
import org.springframework.stereotype.Component;
import java.util.Locale;

@Component
public class TextSanitizer {
    private static final PolicyFactory HTML = new HtmlPolicyBuilder()
            .allowElements("p", "br", "strong", "b", "em", "i", "ul", "ol", "li").toFactory();
    private static final PolicyFactory PLAIN = new HtmlPolicyBuilder().toFactory();

    public String html(String value) { return HTML.sanitize(value).strip(); }
    public String plain(String value) {
        StringBuilder text = new StringBuilder();
        HtmlStreamEventReceiver receiver = new HtmlStreamEventReceiver() {
            public void openDocument() {}
            public void closeDocument() {}
            public void openTag(String name, java.util.List<String> attributes) {}
            public void closeTag(String name) {}
            public void text(String value) { text.append(value); }
        };
        HtmlSanitizer.sanitize(value, PLAIN.apply(receiver));
        return text.toString().strip();
    }
    public static String normalizeName(String name) {
        return name.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
