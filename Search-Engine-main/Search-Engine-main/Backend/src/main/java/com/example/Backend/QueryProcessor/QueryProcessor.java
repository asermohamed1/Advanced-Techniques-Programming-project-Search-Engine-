package com.example.Backend.QueryProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import com.example.Backend.Crawler.Crawler;
import com.example.Backend.Crawler.CrawlerController;
import com.example.Backend.Indexer.Indexer;
import com.example.Backend.Indexer.Indexer.DocumentEntry;
import com.example.Backend.Indexer.IndexerController;
import com.example.Backend.Tokenizer.Tokenizer;
import com.example.Backend.Tokenizer.Tokenizer.TokenPosition;

@Component
public class QueryProcessor {

    public Map<String, PageMetaData> ResultedUrls = new HashMap<>();
    private final Tokenizer tokenizer;
    private final IndexerController indexerController;
    private final CrawlerController crawlerController;

    public QueryProcessor(Tokenizer tokenizer, IndexerController indexerController, CrawlerController crawlerController) {
        this.tokenizer = tokenizer;
        this.indexerController = indexerController;
        this.crawlerController = crawlerController;
    }

    public void SearchQuery(String Query) {
        if (Logic_Search(Query)) return;

        List<String> phrases = new ArrayList<>();
        List<String> logicOperators = new ArrayList<>();
        StringBuilder Terms = new StringBuilder();

        Pattern pattern = Pattern.compile("\"([^\"]+)\"|(AND|OR|NOT)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(Query);

        while (matcher.find()) {
            if (matcher.group(1) != null) {
                phrases.add(matcher.group(1).trim());
            } else if (matcher.group(2) != null) {
                logicOperators.add(matcher.group(2).toUpperCase());
            }
        }

        // Remove phrases and operators from original query to extract standalone words
        String cleaned = Query.replaceAll("\"([^\"]+)\"", "").replaceAll("\\b(AND|OR|NOT)\\b", "").trim();
        Terms.append(cleaned);

        List<String> WordTokens = tokenizer.tokenize(Terms.toString(), "").stream()
                .map(TokenPosition::token)
                .collect(Collectors.toList());
        WordSearch(WordTokens);

        if (!phrases.isEmpty()) {
            PhraseSearchWithLogic(phrases, logicOperators);
        }
    }

    void WordSearch(List<String> WordTokens) {
        for (String token : WordTokens) {
            Optional<Indexer> pages = indexerController.GetAllPagesByToken(token);
            if (pages.isPresent()) {
                List<DocumentEntry> links = pages.get().getDocuments();
                for (DocumentEntry link : links) {
                    ResultedUrls.putIfAbsent(link.getDocumentId(), new PageMetaData());
                    ResultedUrls.get(link.getDocumentId()).Words
                            .add(new SearchData(link.getTf(), pages.get().getIdf(), link.getPositions()));
                    ResultedUrls.get(link.getDocumentId()).rank = crawlerController.GetPagePopularity(link.getDocumentId());
                }
            }
        }
    }

    public String Generate_Snippit(String url) {
        String html = crawlerController.GetPageHtml(url);
        if (html.equals("No Html")) return "";

        String text = Jsoup.parse(html).text();
        PageMetaData meta = ResultedUrls.get(url);

        if (meta == null || meta.Words.isEmpty()) {
            return text.substring(0, Math.min(150, text.length())) + (text.length() > 150 ? "..." : "");
        }

        List<Integer> allPositions = new ArrayList<>();
        for (SearchData word : meta.Words) {
            allPositions.addAll(word.Positions);
        }
        Collections.sort(allPositions);

        int snippetStart = findBestSnippetPosition(allPositions, text.length());
        String[] words = text.split("\\s+");
        int start = Math.max(0, snippetStart - 12);
        int end = Math.min(words.length, snippetStart + 13);

        StringBuilder snippet = new StringBuilder();
        Set<String> queryTerms = meta.Words.stream()
                .map(w -> text.substring(Math.max(0, w.Positions.get(0)), Math.min(text.length(), w.Positions.get(0) + 1)))
                .collect(Collectors.toSet());

        for (int i = start; i < end; i++) {
            String word = words[i];
            if (queryTerms.contains(word)) {
                snippet.append("<b>").append(word).append("</b> ");
            } else {
                snippet.append(word).append(" ");
            }
        }

        return snippet.toString().trim() + (end < words.length ? "..." : "");
    }

    private int findBestSnippetPosition(List<Integer> positions, int textLength) {
        if (positions.isEmpty()) return 0;
        int windowSize = 100;
        int maxCount = 0;
        int bestPos = positions.get(0);

        for (int i = 0; i < positions.size(); i++) {
            int current = positions.get(i);
            int count = 1;
            for (int j = i + 1; j < positions.size() && positions.get(j) <= current + windowSize; j++) {
                count++;
            }
            if (count > maxCount) {
                maxCount = count;
                bestPos = current;
            }
        }
        return bestPos;
    }

    private void PhraseSearchWithLogic(List<String> phrases, List<String> operators) {
        if (phrases.size() == 1) {
            Map<String, List<Integer>> result = getPhraseMatches(phrases.get(0));
            addToResultedUrls(result, 2.0);
        } else if (phrases.size() == 2 && operators.size() == 1) {
            Map<String, List<Integer>> first = getPhraseMatches(phrases.get(0));
            Map<String, List<Integer>> second = getPhraseMatches(phrases.get(1));
            Set<String> finalUrls = new HashSet<>();

            switch (operators.get(0)) {
                case "AND":
                    finalUrls.addAll(first.keySet());
                    finalUrls.retainAll(second.keySet());
                    break;
                case "OR":
                    finalUrls.addAll(first.keySet());
                    finalUrls.addAll(second.keySet());
                    break;
                case "NOT":
                    finalUrls.addAll(first.keySet());
                    finalUrls.removeAll(second.keySet());
                    break;
            }

            Map<String, List<Integer>> combined = new HashMap<>();
            for (String url : finalUrls) {
                List<Integer> allPositions = new ArrayList<>();
                if (first.containsKey(url)) allPositions.addAll(first.get(url));
                if (second.containsKey(url)) allPositions.addAll(second.get(url));
                combined.put(url, allPositions);
            }
            addToResultedUrls(combined, 2.0);
        }
    }

    private Map<String, List<Integer>> getPhraseMatches(String phrase) {
        Map<String, List<Integer>> matches = new HashMap<>();
        List<Crawler> crawlers = crawlerController.GetAllIndexedUrls();
        String searchPhrase = phrase.toLowerCase();

        for (Crawler crawler : crawlers) {
            String html = crawlerController.GetPageHtml(crawler.getUrl());
            if (html == null || html.isEmpty()) continue;

            String text = Jsoup.parse(html).text().toLowerCase();
            int wordCount = text.split("\\s+").length;

            if (text.contains(searchPhrase)) {
                List<Integer> positions = GetPositions(text, searchPhrase);
                if (!positions.isEmpty()) {
                    matches.put(crawler.getUrl(), positions);
                }
            }
        }
        return matches;
    }

    private void addToResultedUrls(Map<String, List<Integer>> matches, double tfMultiplier) {
        int totalPages = crawlerController.GetAllIndexedUrls().size();
        int docCount = matches.size();
        double idf = Math.log((double) totalPages / (docCount == 0 ? 1 : docCount));

        for (Map.Entry<String, List<Integer>> entry : matches.entrySet()) {
            String url = entry.getKey();
            List<Integer> positions = entry.getValue();
            double tf = (double) positions.size() / crawlerController.GetPageHtml(url).split("\\s+").length;

            ResultedUrls.putIfAbsent(url, new PageMetaData());
            ResultedUrls.get(url).Words.add(new SearchData(tf * tfMultiplier, idf, positions));
            ResultedUrls.get(url).rank = crawlerController.GetPagePopularity(url);
        }
    }

    private List<Integer> GetPositions(String text, String phrase) {
        List<Integer> positions = new ArrayList<>();
        int pos = 0;
        while ((pos = text.indexOf(phrase, pos)) != -1) {
            positions.add(pos);
            pos += phrase.length();
        }
        return positions;
    }

    public boolean Logic_Search(String Query) {
        return false;
    }

    public class PageMetaData {
        public Set<SearchData> Words;
        public double rank;

        public PageMetaData() {
            Words = new HashSet<>();
        }
    }

    public class SearchData {
        public double tf;
        public double idf;
        List<Integer> Positions;

        public SearchData(double tf, double idf, List<Integer> Positions) {
            this.tf = tf;
            this.idf = idf;
            this.Positions = Positions;
        }
    }
}
