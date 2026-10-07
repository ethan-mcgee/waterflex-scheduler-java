package dev.waterflex.benchmark;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.optimizer.SolverEngine;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Pinned 2.6.0 XML import. Aggregated sentinel -1 values never become sub-run measurements. */
public record NativeReport(int subSingleCount, int subSingleIndex, long seed, int phaseCount, String moveThreads,
        long spentCapMs, long solveMs, long scoreCalculationCount, long moveEvaluationCount, String score) {
    public static NativeReport read(Path path,SolverEngine.Definition expected,long cap) {
        try {
            var factory=DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            var root=Required.value(factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement());
            Element solver=one(root,"solverBenchmarkResult"), config=one(solver,"solverConfig"),result=one(solver,"subSingleBenchmarkResult");
            if(!value(result,"succeeded").equals("true")) throw new IllegalArgumentException("Native sub-run failed");
            int phases=0;
            for(Node child=config.getFirstChild();child!=null;child=child.getNextSibling()) if(SetHolder.PHASES.contains(child.getNodeName())) phases++;
            var report=new NativeReport(Math.toIntExact(number(solver,"subSingleCount")),Math.toIntExact(number(result,"subSingleBenchmarkIndex")),
                    number(config,"randomSeed"),phases,value(config,"moveThreadCount"),number(direct(config,"termination"),"millisecondsSpentLimit"),
                    number(result,"timeMillisSpent"),number(result,"scoreCalculationCount"),number(result,"moveEvaluationCount"),value(result,"score"));
            if(report.subSingleCount()!=1 || report.subSingleIndex()!=0 || report.seed()!=expected.seed() || report.phaseCount()!=(expected.construction() ? 2 : 1)
                    || !report.moveThreads().equals("NONE") || report.spentCapMs()!=cap) throw new IllegalArgumentException("Native effective configuration mismatch");
            return report;
        } catch(java.io.IOException | javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException failure) {
            throw new IllegalArgumentException("Cannot import native report",failure);
        }
    }
    private static final class SetHolder { static final java.util.Set<String> PHASES=Required.value(java.util.Set.of("constructionHeuristic","localSearch","customPhase","exhaustiveSearch","partitionedSearch")); }
    private static Element one(Element parent,String tag) {
        var nodes=parent.getElementsByTagName(tag);
        if(nodes.getLength()!=1) throw new IllegalArgumentException("Expected one native report field: "+tag);
        return (Element)Required.value(nodes.item(0));
    }
    private static Element direct(Element parent,String tag) {
        Element found=null;
        for(Node child=parent.getFirstChild();child!=null;child=child.getNextSibling()) if(child instanceof Element element && tag.equals(element.getTagName())) {
            if(found!=null) throw new IllegalArgumentException("Duplicate native scope field: "+tag);found=element;
        }
        return Required.value(found,"Missing native scope field: "+tag);
    }
    private static String value(Element parent,String tag) { return Required.value(one(parent,tag).getTextContent()); }
    private static long number(Element parent,String tag) {
        long value=Long.parseLong(value(parent,tag));if(value<0) throw new IllegalArgumentException("Unavailable native metric: "+tag);return value;
    }
}
