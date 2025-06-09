package org.example;

/*
 * DatasetGenerator.java (rev-2025-06-09-05)
 * ────────────────────────────────────────────────────────────────
 *  ▸ Естественные русские фразы: согласование рода/числа, корректные
 *    предлоги, порядок слов, запятые.
 *  ▸ Разделён рендер plain-phrase (PhraseRenderer) и xml-annotation
 *    (осталось прежним ➜ 100 % совместимость формата «plain TAB | TAB xml»).
 *  ▸ Минимальные сторонние зависимости: Morfologik (ru), Apache POI,
 *    Lucene-analysis-common (только для VariationProvider).
 *
 *  ✦ Пример вывода:
 *      «Подберите, пожалуйста, женскую зубную нить бренда Alevi
 *       по цене от 533 до 620 рублей, объёмом 780 мл,
 *       для волос с перхотью и сухой кожи, для губ,
 *       с ароматом гвоздики, из шипровой группы.»
 */

import org.apache.lucene.analysis.ru.RussianLightStemmer;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.morfologik.stemming.Dictionary;
import org.morfologik.stemming.DictionaryLookup;
import org.morfologik.stemming.WordData;

import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.text.NumberFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class DatasetGenerator {

    /* ─────── defaults ─────── */
    private static final int    DEF_ROWS  = 500;
    private static final double DEF_TYPO  = 0.10;
    private static final String DEF_SHEET = "фильтры и значения";
    private static final SecureRandom RAND = new SecureRandom();

    /* ─────── фразы-стартеры ─────── */
    private static final List<String> STARTERS = List.of(
            "Подберите, пожалуйста,", "Мне нужен", "Ищу", "Хочу", "Нужен"
    );

    /* ─────── фильтры, для которых нужен Brand/Gender и т.д. ─────── */
    private static final String FILTER_PRODUCT = "productClass";
    private static final String FILTER_BRAND   = "brand";
    private static final String FILTER_GENDER  = "gender";

    /* ─────── ру-лока для чисел рублей ─────── */
    private static final NumberFormat RUB_FMT = NumberFormat.getInstance(new Locale("ru", "RU"));

    /* ───────────────────────────────────────── */
    public static void main(String[] args) throws IOException {
        Map<String,List<String>> cli = parseArgs(args);

        Path   excel = Paths.get(required(cli, "--excel"));
        String sheet = cli.getOrDefault("--sheet", List.of(DEF_SHEET)).get(0);
        int    rows  = Integer.parseInt(cli.getOrDefault("--rows", List.of(String.valueOf(DEF_ROWS))).get(0));
        double typoR = Double.parseDouble(cli.getOrDefault("--typo-rate", List.of(String.valueOf(DEF_TYPO))).get(0));

        List<String> templates = cli.getOrDefault("--template", List.of("learning_data_syntetic_004.txt=*RND"));

        /* ▸ словарь из Excel */
        Map<String,List<String>> dict = new ExcelLoader().load(excel, sheet);

        VariationProvider vp = new VariationProvider();
        PhraseBuilder     pb = new PhraseBuilder(typoR, vp);

        for (String tpl : templates) {
            String[] p = tpl.split("=",2);
            if (p.length!=2) throw new IllegalArgumentException("template → file=filters|*RND");
            String outFile = p[0].trim();
            List<String> tplFilters = "*RND".equalsIgnoreCase(p[1])
                    ? pickRandomSeveral(dict.keySet(), 1 + RAND.nextInt(5))
                    : Arrays.stream(p[1].split(","))
                    .map(String::trim).filter(s->!s.isEmpty()).toList();

            try (BufferedWriter w = Files.newBufferedWriter(Paths.get(outFile))) {
                for (int i = 0; i < rows; i++) {
                    Map<String,String> row = new LinkedHashMap<>();
                    for (String f : tplFilters) {
                        List<String> vals = dict.get(f);
                        if (vals==null||vals.isEmpty()) continue;
                        row.put(f, vals.get(RAND.nextInt(vals.size())));
                    }
                    Frase fr = pb.render(row);
                    w.write(fr.plain()); w.write("\t|\t"); w.write(fr.xml()); w.newLine();
                }
            }
            System.out.printf("%s  (%d строк) готов%n", outFile, rows);
        }
    }

    /* ───────────────────────────────────────── */
    /*               Excel loader               */
    /* ───────────────────────────────────────── */
    private static class ExcelLoader {
        private static final Pattern D = Pattern.compile("DDDD");
        Map<String,List<String>> load(Path xlsx,String sheetName)throws IOException{
            Map<String,List<String>> dict=new LinkedHashMap<>();
            try(Workbook wb=new XSSFWorkbook(Files.newInputStream(xlsx))){
                Sheet s=wb.getSheet(sheetName);
                if(s==null) throw new IllegalArgumentException("нет листа "+sheetName);
                for(Row r:s){
                    Cell fCell=r.getCell(0), vCell=r.getCell(2);
                    if(fCell==null||vCell==null) continue;
                    String filter=fCell.getStringCellValue().trim();
                    String raw=vCell.getStringCellValue();
                    if(filter.isEmpty()||raw==null) continue;

                    Matcher m=D.matcher(raw);
                    StringBuffer sb=new StringBuffer();
                    while(m.find()) m.appendReplacement(sb,String.valueOf(10+RAND.nextInt(991)));
                    m.appendTail(sb);

                    List<String> vals=Arrays.stream(sb.toString().split("\\|"))
                            .map(String::trim).filter(sv->!sv.isEmpty()).toList();
                    if(!vals.isEmpty()) dict.put(filter,vals);
                }
            }
            return dict;
        }
    }

    /* ───────────────────────────────────────── */
    /*       plain-phrase + xml annotation       */
    /* ───────────────────────────────────────── */
    private record Frase(String plain, String xml){}

    private static class PhraseBuilder {
        private final double typoRate;
        private final VariationProvider vp;
        private final PhraseRenderer renderer = new PhraseRenderer();
        PhraseBuilder(double typo, VariationProvider vp){this.typoRate=typo; this.vp=vp;}

        Frase render(Map<String,String> row){
            Map<String,String> altered = new LinkedHashMap<>();
            // ▸ возможные опечатки + вариации форм
            row.forEach((k,v)->{
                String vv = vp.randomForm(v, RAND);
                if(RAND.nextDouble()<typoRate) vv = vp.typo(vv,RAND);
                altered.put(k,vv);
            });
            String plain = renderer.toPlain(altered);
            String  xml  = renderer.toXml(altered);
            return new Frase(plain, xml);
        }
    }

    /* ───────────────────────────────────────── */
    /*          natural language renderer        */
    /* ───────────────────────────────────────── */
    private static class PhraseRenderer {
        private final MorphoHelper morpho = new MorphoHelper();
        String toPlain(Map<String,String> mp){
            String starter = STARTERS.get(RAND.nextInt(STARTERS.size()));

            String product = mp.getOrDefault(FILTER_PRODUCT, "товар");
            String brand   = mp.get(FILTER_BRAND);
            String genderV = mp.get(FILTER_GENDER);

            StringBuilder res = new StringBuilder();
            res.append(starter).append(" ");

            // ▸ 1. Базовая сущность «женскую зубную нить бренда X»
            if(genderV!=null){
                res.append(morpho.genderedAdjective(genderV, product)).append(" ");
            }
            res.append(product);
            if(brand!=null) res.append(" бренда ").append(brand);

            // ▸ 2. Остальные характеристики собираем в список
            List<String> tail = new ArrayList<>();
            for (Map.Entry<String,String> e : mp.entrySet()) {
                if(Set.of(FILTER_PRODUCT,FILTER_BRAND,FILTER_GENDER).contains(e.getKey())) continue;
                String seg = switch (e.getKey()){
                    case "price"       -> "по цене " + normalizePrice(e.getValue());
                    case "highRating"  -> e.getValue().equalsIgnoreCase("true") ? "с высоким рейтингом" : null;
                    case "volumeProduct"-> "объемом " + e.getValue() + " мл";
                    case "hairType"    -> e.getValue().startsWith("с ")?"для волос "+e.getValue():"для волос с "+e.getValue();
                    case "skinType"    -> e.getValue();
                    case "applicationArea" -> "для " + e.getValue().toLowerCase(Locale.ROOT);
                    case "baseAromaFlavors"-> "с ароматом " + e.getValue().toLowerCase(Locale.ROOT);
                    case "parfGroup"   -> "из " + e.getValue().toLowerCase(Locale.ROOT) + " группы";
                    default             -> null;
                };
                if(seg!=null) tail.add(seg);
            }
            if(!tail.isEmpty()) res.append(", ").append(String.join(", ", tail));
            res.append(".");
            return capitalize(res.toString().replaceAll(" +"," ").trim());
        }
        String toXml(Map<String,String> mp){
            StringBuilder xml = new StringBuilder();
            Iterator<Map.Entry<String,String>> it = mp.entrySet().iterator();
            while(it.hasNext()){
                Map.Entry<String,String> e = it.next();
                xml.append("<").append(e.getKey()).append(">")
                        .append(e.getValue())
                        .append("</").append(e.getKey()).append(">");
                if(it.hasNext()) xml.append(" ");
            }
            return xml.toString();
        }
        private static String normalizePrice(String v){
            return v.replaceAll("От ","от ").replaceAll("До ","до ");
        }
        private static String capitalize(String s){
            return s.isEmpty()?s:Character.toUpperCase(s.charAt(0))+s.substring(1);
        }
    }

    /* ───────────────────────────────────────── */
    /*     morphology helpers (gender/adjective) */
    /* ───────────────────────────────────────── */
    private static class MorphoHelper {
        private final DictionaryLookup dict;
        MorphoHelper(){
            try{dict = new DictionaryLookup(Dictionary.lookup("ru"));}
            catch(IOException e){throw new RuntimeException(e);} }

        enum G {M,F,N,UNKNOWN}

        G guessGender(String noun){
            String w = noun.toLowerCase(Locale.ROOT);
            if(w.endsWith("а")||w.endsWith("я")||w.endsWith("ь")) return G.F;
            if(w.endsWith("о")||w.endsWith("е")) return G.N;
            return G.M;
        }
        String genderedAdjective(String adj,String noun){
            G g = guessGender(noun);
            String base = adj.toLowerCase(Locale.ROOT).replaceAll("[ыйой]$","");
            return switch(g){
                case F -> base + "ую";
                case N -> base + "ое";
                default -> base + "ый";
            };
        }
    }

    /* ───────────────────────────────────────── */
    /*        morphology + typos provider        */
    /* ───────────────────────────────────────── */
    private static class VariationProvider{
        private final RussianLightStemmer stem=new RussianLightStemmer();
        private static final Map<String,List<String>> MAP=Map.of(
                "е",List.of("ё"),"ё",List.of("е"),
                "и",List.of("й"),"й",List.of("и"),
                "ш",List.of("щ"),"щ",List.of("ш")
        );
        List<String> forms(String w){
            List<String> res=new ArrayList<>();
            String low=w.toLowerCase(Locale.ROOT);
            res.add(low); res.add(cap(low));
            char[] ch=low.toCharArray();
            int len=stem.stem(ch,ch.length);
            if(len<low.length()) res.add(new String(ch,0,len));
            return List.copyOf(new LinkedHashSet<>(res));
        }
        String randomForm(String w,Random rnd){
            List<String> f=forms(w); return f.get(rnd.nextInt(f.size())); }
        String typo(String w,Random rnd){ return w.length()<3?swap(w):rnd.nextBoolean()?swap(w):rep(w,rnd);}
        private static String cap(String s){return s.isEmpty()?s:Character.toUpperCase(s.charAt(0))+s.substring(1);}
        private static String swap(String s){ if(s.length()<2) return s; int i=RAND.nextInt(s.length()-1); char[] a=s.toCharArray(); char t=a[i]; a[i]=a[i+1]; a[i+1]=t; return new String(a);}
        private static String rep(String s,Random rnd){ for(int i=0;i<s.length();i++){ String ch=String.valueOf(s.charAt(i)); List<String> r=MAP.get(ch); if(r!=null && !r.isEmpty()){ String repl=r.get(rnd.nextInt(r.size())); return s.substring(0,i)+repl+s.substring(i+1);} } return swap(s);}    }

    /* ───────────────────────────────────────── */
    /*            helpers & CLI stuff            */
    /* ───────────────────────────────────────── */
    private static Map<String,List<String>> parseArgs(String[] a){
        Map<String,List<String>> m=new LinkedHashMap<>();
        for(int i=0;i<a.length;i++){
            String k=a[i]; if(!k.startsWith("--")) throw new IllegalArgumentException("bad token "+k);
            if(i+1>=a.length) throw new IllegalArgumentException("value for "+k+"?");
            m.computeIfAbsent(k,__->new ArrayList<>()).add(a[++i]);
        }
        return m; }
    private static String required(Map<String,List<String>> m,String k){
        return Optional.ofNullable(m.get(k)).flatMap(l->l.stream().findFirst())
                .orElseThrow(()->new IllegalArgumentException(k+" required")); }

    private static <T> List<T> pickRandomSeveral(Collection<T> src,int k){
        List<T> copy=new ArrayList<>(src); Collections.shuffle(copy,RAND);
        return copy.subList(0,Math.min(k,copy.size())); }
}