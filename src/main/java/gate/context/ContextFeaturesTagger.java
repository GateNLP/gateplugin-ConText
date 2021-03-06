/**
* Copyright 2015 South London and Maudsley NHS Trust and King's College London
*
* Based on Wendy Chapman's NegEx algorithm, drawing on implementations by Imre
* Solti and Xiuyun Shen
*
* Licensed under the Apache License, Version 2.0 (the "License");
* 
* you may not use this file except in compliance with the License. You may
* obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
* WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
* License for the specific language governing permissions and limitations
* under the License.
*/



package gate.context;

import gate.*;
import gate.creole.ANNIEConstants;
import gate.creole.AbstractLanguageAnalyser;
import gate.creole.ExecutionException;
import gate.creole.ResourceInstantiationException;
import gate.creole.ResourceReference;
import gate.creole.metadata.CreoleParameter;
import gate.creole.metadata.CreoleResource;
import gate.creole.metadata.Optional;
import gate.creole.metadata.RunTime;

import java.text.NumberFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.net.URL;
import java.util.ArrayList;


import org.apache.log4j.Logger;

/**
 * A GATE PR based on the ConText algorithm (see Harkema etal, 2009)
 */
@CreoleResource(
  name = "Context implementation", 
  comment = "Determine Negation, Experiencer, and Temporal Status from Clinical Reports", 
  helpURL = "https://github.com/GateNLP/gateplugin-ConText/wiki")
public class ContextFeaturesTagger extends AbstractLanguageAnalyser {

    private transient Logger logger = Logger.getLogger(this.getClass().getName());

    private String outputASName;

    private String inputASName;

    private String inputAnnotationName;

    private String conceptAnnotationName;

    private ResourceReference triggerListsUrl;

    private String encoding;

    private LanguageAnalyser triggerResources;

    public ResourceReference getTriggerListsUrl() {
      return triggerListsUrl;
    }

    @CreoleParameter(comment = "Gazetteer definition used to look up triggers.", suffixes = ".def",
        defaultValue = "resources/gazetteers/triggers/triggers.def")
    public void setTriggerListsUrl(ResourceReference triggerListsUrl) {
      this.triggerListsUrl = triggerListsUrl;
    }

    public String getEncoding() {
      return encoding;
    }

    @CreoleParameter(comment = "Encoding used to load the triggers gazetteer", defaultValue = "UTF-8")
    public void setEncoding(String encoding) {
      this.encoding = encoding;
    }

    public Resource init() throws ResourceInstantiationException {
        FeatureMap hidden = Factory.newFeatureMap();
        Gate.setHiddenAttribute(hidden, true);
        FeatureMap params = Factory.newFeatureMap();
        params.put("listsURL", triggerListsUrl);
        params.put("encoding", encoding);
        params.put("caseSensitive", Boolean.FALSE);
        params.put("gazetteerFeatureSeparator", "");
        triggerResources = (LanguageAnalyser) Factory.createResource("gate.creole.gazetteer.DefaultGazetteer", params, hidden);
        return this;
    }


    @Override
    public void execute() throws ExecutionException {
        interrupted = false;
        if (document == null) throw new ExecutionException("No Document provided!");
        AnnotationSet inputAnnotationSet = document.getAnnotations(inputASName);
        AnnotationSet outputAnnotationSet = document.getAnnotations(outputASName);
        AnnotationSet inputAnnotations = inputAnnotationSet.get(getInputAnnotationName());

        triggerResources.setDocument(document);
        try {
            triggerResources.setParameterValue("annotationSetName", outputASName);
            triggerResources.execute();
        } catch (Exception ex) {
            throw new ExecutionException(ex);
        } finally {
            // make sure we release the document properly
            triggerResources.setDocument(null);
        }

        if (inputAnnotations != null && !inputAnnotations.isEmpty()) {
            // get concept annotations
            AnnotationSet textConceptAnnotations = inputAnnotationSet.get(getConceptAnnotationName());
            if (textConceptAnnotations != null && !textConceptAnnotations.isEmpty()) {
                // assign default values to concepts
                assignDefaultValuesToConcepts(textConceptAnnotations);
                // remove triggers appeared within pseudo triggers
                Set<Long> pseudoTriggersBoundaries = cleanupTriggers();
                for (Annotation sentenceLikeAnnotation : inputAnnotations) {
                    // Sentence is an expecting annotation. Populate it with default values first
                    Node sentenceStartNode = sentenceLikeAnnotation.getStartNode();
                    Node sentenceEndNode = sentenceLikeAnnotation.getEndNode();
                    AnnotationSet sentenceContentAnnotations = inputAnnotationSet.get(sentenceStartNode.getOffset(), sentenceEndNode.getOffset());
                    AnnotationSet triggerAnnotationsSet = outputAnnotationSet.get(sentenceStartNode.getOffset(), sentenceEndNode.getOffset()).get(Constants.ANNOTATION_NAME_TRIGGER);
                    List<Annotation> triggerAnnotations = triggerAnnotationsSet.inDocumentOrder();
                    // process trigger annotation
                    if (triggerAnnotations != null && !triggerAnnotations.isEmpty()) {
                        for (Annotation triggerAnnotation : triggerAnnotations) {
                            if ((pseudoTriggersBoundaries.contains(triggerAnnotation.getStartNode().getOffset())) ||
                                    (pseudoTriggersBoundaries.contains(triggerAnnotation.getEndNode().getOffset()))) {
                                // skip triggers which are within pseudo-triggers
                                continue;
                            }
                            FeatureMap featureMap = triggerAnnotation.getFeatures();
                            String majorType = (String) featureMap.get(ANNIEConstants.LOOKUP_MAJOR_TYPE_FEATURE_NAME);
                            String minorType = (String) featureMap.get(ANNIEConstants.LOOKUP_MINOR_TYPE_FEATURE_NAME);
                            if (minorType.equalsIgnoreCase(Constants.FEATURE_NAME_PSEUDO_TRIGGER)) {
                                // skip pseudo triggers
                                continue;
                            }
                            // ok. Now it's necessary to detect scope of trigger term
                            Long potentialScopeStart = triggerAnnotation.getStartNode().getOffset();
                            Long potentialScopeEnd = sentenceEndNode.getOffset();
                            AnnotationSet terminationTerms = sentenceContentAnnotations.get(potentialScopeStart, potentialScopeEnd).get("Termination-Term");
                            
                            //Xingyi Modifications:
                            List<Annotation> listAnnos = new ArrayList<Annotation>();

                            for (Annotation triggerTMP : triggerAnnotations) {
                            	 for (Annotation terminationTMP : terminationTerms) {
                            		 if(terminationTMP.overlaps(triggerTMP)){
                            			 listAnnos.add(terminationTMP);
                            		 }
                            	 }
                            }
                            for (Annotation toremove:listAnnos){
                            	inputAnnotationSet.remove(toremove);
                            	
                            }
                            
                            terminationTerms = inputAnnotationSet.get(sentenceStartNode.getOffset(), sentenceEndNode.getOffset()).get(potentialScopeStart, potentialScopeEnd).get("Termination-Term");
                            
                            
                            //Modification End
                            
                            
                            
                            if (terminationTerms == null || terminationTerms.isEmpty()) {
                                // default scope expanded until end of the current sentence. Assign Context feature to all Indexed Terms within scope detected and break sentence processing
                                AnnotationSet scopeConcepts = textConceptAnnotations.get(potentialScopeStart, potentialScopeEnd);
                                AnnotationSet scopeTriggers = triggerAnnotationsSet.get(potentialScopeStart, potentialScopeEnd);
                                String experiencer = getExperiencer(potentialScopeStart, potentialScopeEnd, scopeTriggers);
                                String temporality = getTemporality(potentialScopeStart, potentialScopeEnd, scopeTriggers);
                                String negated = getNegated(potentialScopeStart, potentialScopeEnd, scopeTriggers);
                                for (Annotation conceptAnnotation : scopeConcepts) {
                                    conceptAnnotation.getFeatures().put(Constants.NEGATION_VALUE, negated);
                                    conceptAnnotation.getFeatures().put(Constants.TEMPORALITY_VALUE, temporality);
                                    conceptAnnotation.getFeatures().put(Constants.EXPERIENCER_VALUE, experiencer);
                                }
                                break;
                            } else {
                                // we have a termination term/terms detected.
                                List<Annotation> sortedTerminationTriggers = terminationTerms.inDocumentOrder();
                                // lets take first termination term and limit scope to it
                                Annotation terminationTerm = sortedTerminationTriggers.get(0);
                                potentialScopeEnd = terminationTerm.getStartNode().getOffset();
                                AnnotationSet scopeTriggers = triggerAnnotationsSet.get(potentialScopeStart, potentialScopeEnd);
                                assignContextFeatures(textConceptAnnotations, scopeTriggers, potentialScopeStart, potentialScopeEnd);
                            }
                        }
                    }
                }
            }
        }
        long startTime = System.currentTimeMillis();
        fireStatusChanged("Context in " + document.getName());
        fireProgressChanged(0);
        fireProcessFinished();
        fireStatusChanged(document.getName() + " tagged with Contextual Features in " + NumberFormat.getInstance().format((double) (System.currentTimeMillis() - startTime) / 1000) + " seconds!");
    }

    /**
     * Method to define pseudo trigger boundaries. Token boundaries used because
     * Trigger term could be completely within Pseudo-Trigger. For example Pseudo-Trigger potentially could have 3 words
     * and Trigger size just 1 word
     */
    private Set<Long> cleanupTriggers() {
        Set<Long> boundaries = new HashSet<Long>();
        List<Annotation> triggerAnnotations = getDocument().getAnnotations(outputASName).get(Constants.ANNOTATION_NAME_TRIGGER).inDocumentOrder();
        for (Annotation triggerAnnotation : triggerAnnotations) {
            String minorType = (String) triggerAnnotation.getFeatures().get(ANNIEConstants.LOOKUP_MINOR_TYPE_FEATURE_NAME);
            if (minorType.equalsIgnoreCase(Constants.FEATURE_NAME_PSEUDO_TRIGGER)) {
                Long startOffset = triggerAnnotation.getStartNode().getOffset();
                Long endOffset = triggerAnnotation.getEndNode().getOffset();
                AnnotationSet tokens = getDocument().getAnnotations(inputASName).get(startOffset, endOffset).get(ANNIEConstants.TOKEN_ANNOTATION_TYPE);
                if (tokens != null) {
                    for (Annotation token : tokens) {
                        boundaries.add(token.getStartNode().getOffset());
                        boundaries.add(token.getEndNode().getOffset());
                    }
                }
            }
        }
        return boundaries;
    }

    /**
     * The input to the ConText algorithm is a sentence (or a text with marked-up sentence boundaries)
     * in which clinical conditions have been indexed and default values have been assigned
     * to their contextual properties.
     *
     * @param textConceptAnnotations
     */
    private void assignDefaultValuesToConcepts(AnnotationSet textConceptAnnotations) {
        for (Annotation conceptAnnotation : textConceptAnnotations) {
            conceptAnnotation.getFeatures().put(Constants.NEGATION_VALUE, Constants.DEFAULT_NEGATION_VALUE);
            conceptAnnotation.getFeatures().put(Constants.TEMPORALITY_VALUE, Constants.DEFAULT_TEMPORALITY_VALUE);
            conceptAnnotation.getFeatures().put(Constants.EXPERIENCER_VALUE, Constants.DEFAULT_EXPERIENCER_VALUE);
        }

    }


    /**
     * @param conceptAnnotations
     * @param sentenceContentAnnotations
     * @param potentialScopeStart
     * @param potentialScopeEnd
     */
    private void assignContextFeatures(AnnotationSet conceptAnnotations, AnnotationSet scopeTriggers, Long potentialScopeStart, Long potentialScopeEnd) {
        AnnotationSet scopeConcepts = conceptAnnotations.get(potentialScopeStart, potentialScopeEnd);
        for (Annotation conceptAnnotation : scopeConcepts) {
            String experiencer = getExperiencer(potentialScopeStart, potentialScopeEnd, scopeTriggers);
            String temporality = getTemporality(potentialScopeStart, potentialScopeEnd, scopeTriggers);
            String negated = getNegated(potentialScopeStart, potentialScopeEnd, scopeTriggers);

            conceptAnnotation.getFeatures().put(Constants.NEGATION_VALUE, negated);
            conceptAnnotation.getFeatures().put(Constants.TEMPORALITY_VALUE, temporality);
            conceptAnnotation.getFeatures().put(Constants.EXPERIENCER_VALUE, experiencer);
        }
    }

    /**
     * Method to detect Negation feature
     *
     * @param scopeStart
     * @param scopeEnd
     * @param triggerAnnotationSet
     * @return
     */
    private String getNegated(Long scopeStart, Long scopeEnd, AnnotationSet triggerAnnotationSet) {
        String negated = null;
        FeatureMap featureMap = Factory.newFeatureMap();
        featureMap.put(ANNIEConstants.LOOKUP_MAJOR_TYPE_FEATURE_NAME, "negation");
        AnnotationSet negatedAnnotations = triggerAnnotationSet.get(scopeStart, scopeEnd).get(Constants.ANNOTATION_NAME_TRIGGER, featureMap);
        int allNegatedAnnotationsSize = (negatedAnnotations == null || negatedAnnotations.isEmpty()) ? 0 : negatedAnnotations.size();
        featureMap.put(ANNIEConstants.LOOKUP_MINOR_TYPE_FEATURE_NAME, Constants.FEATURE_NAME_PSEUDO_TRIGGER);
        negatedAnnotations = triggerAnnotationSet.get(scopeStart, scopeEnd).get(Constants.ANNOTATION_NAME_TRIGGER, featureMap);
        int pseudoNegatedAnnotationsSize = (negatedAnnotations == null || negatedAnnotations.isEmpty()) ? 0 : negatedAnnotations.size();
        if (pseudoNegatedAnnotationsSize == 0 && allNegatedAnnotationsSize > 0) {
            negated = "Negated";
        }
        return (negated == null || negated.length() == 0) ? Constants.DEFAULT_NEGATION_VALUE : negated;
    }

    /**
     * Method to detect Temporality feature
     *
     * @param scopeStart
     * @param scopeEnd
     * @param triggerAnnotationSet
     * @return
     */
    private String getTemporality(Long scopeStart, Long scopeEnd, AnnotationSet triggerAnnotationSet) {
        String temporality = null;
        FeatureMap featureMap = Factory.newFeatureMap();
        featureMap.put(ANNIEConstants.LOOKUP_MAJOR_TYPE_FEATURE_NAME, "temporality");
        List<Annotation> annotations = triggerAnnotationSet.get(scopeStart, scopeEnd).get(Constants.ANNOTATION_NAME_TRIGGER, featureMap).inDocumentOrder();
        // temporality should be checked for hypothetical or historical
        for (Annotation annotation : annotations) {
            String minorType = (String) annotation.getFeatures().get(ANNIEConstants.LOOKUP_MINOR_TYPE_FEATURE_NAME);
            if (minorType.equalsIgnoreCase(Constants.FEATURE_NAME_PSEUDO_TRIGGER)) {
                continue;
            }
            // TODO: is it a good idea to use only first annotation?
            temporality = minorType;
            break;
        }
        return (temporality == null || temporality.length() == 0) ? Constants.DEFAULT_TEMPORALITY_VALUE : temporality;
    }

    /**
     * Method to detect Experiencer feature
     *
     * @param scopeStart
     * @param scopeEnd
     * @param triggerAnnotationSet
     * @return
     */
    private String getExperiencer(Long scopeStart, Long scopeEnd, AnnotationSet triggerAnnotationSet) {
        String experiencer = null;
        FeatureMap featureMap = Factory.newFeatureMap();
        featureMap.put(ANNIEConstants.LOOKUP_MAJOR_TYPE_FEATURE_NAME, "experiencer");
        List<Annotation> annotations = triggerAnnotationSet.get(scopeStart, scopeEnd).get(Constants.ANNOTATION_NAME_TRIGGER, featureMap).inDocumentOrder();
        if (annotations != null && !annotations.isEmpty()) {
            experiencer = "Other";
        }
        return (experiencer == null || experiencer.length() == 0) ? Constants.DEFAULT_EXPERIENCER_VALUE : experiencer;
    }

    public void cleanup() {
        Factory.deleteResource(this.triggerResources);
    }

    @RunTime
    @Optional
    @CreoleParameter(comment = "The name for annotation set used for the generated annotations")
    public void setOutputASName(String outputAnnotationSetName) {
        this.outputASName = outputAnnotationSetName;
    }

    @RunTime
    @CreoleParameter(comment = "The name for concept annotation(indexed term)")
    public void setConceptAnnotationName(String conceptAnnotationName) {
        this.conceptAnnotationName = conceptAnnotationName;
    }

    @RunTime
    @Optional
    @CreoleParameter(defaultValue = "Sentence", comment = "The name for annotation used storing contextual features")
    public void setInputAnnotationName(String inputAnnotationName) {
        this.inputAnnotationName = inputAnnotationName;
    }

    public String getOutputASName() {
        return outputASName;
    }


    public String getConceptAnnotationName() {
        return conceptAnnotationName;
    }

    public String getInputAnnotationName() {
        return inputAnnotationName;
    }

    public String getInputASName() {
        return inputASName;
    }

    @RunTime
    @Optional
    @CreoleParameter(comment = "The name for annotation set used for the generated annotations")
    public void setInputASName(String inputASName) {
        this.inputASName = inputASName;
    }
}
