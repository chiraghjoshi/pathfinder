package com.redhat.gps.pathfinder.web.api;

import com.codahale.metrics.annotation.Timed;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.redhat.gps.pathfinder.QuestionProcessor;
import com.redhat.gps.pathfinder.domain.ApplicationAssessmentReview;
import com.redhat.gps.pathfinder.domain.Applications;
import com.redhat.gps.pathfinder.domain.Assessments;
import com.redhat.gps.pathfinder.domain.Customer;
import com.redhat.gps.pathfinder.repository.*;
import com.redhat.gps.pathfinder.service.util.Json;
import com.redhat.gps.pathfinder.service.util.MapBuilder;
import com.redhat.gps.pathfinder.web.api.model.*;
import io.swagger.annotations.ApiParam;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Example;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;

/*-
 * #%L
 * Pathfinder
 * $Id:$
 * $HeadURL:$
 * %%
 * Copyright (C) 2018 RedHat
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

@RestController
@RequestMapping("/api/pathfinder")
@Component
public class CustomerAPIImpl extends SecureAPIImpl implements CustomersApi {
    private final Logger log = LoggerFactory.getLogger(CustomerAPIImpl.class);
    private final CustomerRepository custRepo;
    private final ApplicationsRepository appsRepo;
    private final AssessmentsRepository assmRepo;
    private final ReviewsRepository reviewRepository;
    private final MembersRepository membersRepo;
    private String SurveyJSPayload;
    private String SurveyQuestionsJSON;

    @Value("${CUSTOM_QUESTIONS:}")
    private String customQuestionsFileLocation;

    public CustomerAPIImpl(CustomerRepository custRepo,
                           ApplicationsRepository appsRepo,
                           AssessmentsRepository assmRepo,
                           ReviewsRepository reviewRepository,
                           MembersRepository membersRepository) throws IOException {

        super(membersRepository);
        this.custRepo = custRepo;
        this.appsRepo = appsRepo;
        this.assmRepo = assmRepo;
        this.reviewRepository = reviewRepository;
        this.membersRepo = membersRepository;
        this.SurveyJSPayload = "";
        this.SurveyQuestionsJSON = "";
    }

    @PostConstruct
    public void init() throws IOException {
        this.SurveyJSPayload = getSurveyContent();
    }

    public String getSurveyContent() throws IOException {
        String rawQuestionsJson = "";
        String questionsJsonSchema = "";
        String finalJScriptDefn = "";
        String customQuestionsJson = "";

        if ((customQuestionsFileLocation != null) && (!customQuestionsFileLocation.isEmpty())) {
            File customQuestionsFile = new File(customQuestionsFileLocation);
            try (InputStream cqis = new FileInputStream(customQuestionsFile);) {
                customQuestionsJson = getResourceAsString(cqis);
                log.info("Successfully read custom questions file {}", customQuestionsFileLocation);
            } catch (Exception ex) {
                log.error("Unable to load custom questions file {}", customQuestionsFileLocation);
                customQuestionsJson = "";
            }
        }

        try (InputStream is1 = CustomerAPIImpl.class.getClassLoader().getResourceAsStream("questions/base-questions-data-default.json");
             InputStream is2 = CustomerAPIImpl.class.getClassLoader().getResourceAsStream("questions/question-schema.json");
        ) {
            rawQuestionsJson = getResourceAsString(is1);
            questionsJsonSchema = getResourceAsString(is2);
            this.SurveyQuestionsJSON = new QuestionProcessor().GenerateSurveyPages(rawQuestionsJson, customQuestionsJson, questionsJsonSchema);
            log.info("Successfully generated Survey Questions");
        } catch (Exception e) {
            InputStream is3 = CustomerAPIImpl.class.getClassLoader().getResourceAsStream("questions/default-survey-materialised.json");
            this.SurveyQuestionsJSON = getResourceAsString(is3);
            if (is3 != null) is3.close();
            log.error("Unable to find/parse question-data-default...using default-survey...turn on debug for more info");
            log.trace("getSurveyContent raw {} schema {}", rawQuestionsJson, questionsJsonSchema);
            e.printStackTrace();
        }

        try (InputStream is4 = CustomerAPIImpl.class.getClassLoader().getResourceAsStream("questions/application-survey.js");) {
            String surveyJs = getResourceAsString(is4);
            finalJScriptDefn = (surveyJs.replace("$$QUESTIONS_JSON$$", this.SurveyQuestionsJSON));
        } catch (Exception ex) {
            log.error("Unable to process and enrich the question template....FATAL ERROR ", ex);
            System.exit(42);
        }
        return finalJScriptDefn;
    }

    // Non-Swagger api - returns the survey payload
    @RequestMapping(value = "/survey", method = GET, produces = {"application/javascript"})
    public String getSurvey() throws IOException {
        return SurveyJSPayload
                .replaceAll("\"SERVER_URL", "Utils.SERVER+\"")
                .replaceAll("JWT_TOKEN", "\"+jwtToken+\"");
    }

    // Get Members
    // GET: /api/pathfinder/customers/{customerId}/member/
    public ResponseEntity<List<MemberType>> customersCustIdMembersGet(@ApiParam(required = true) @PathVariable("custId") String custId) {
        return new MemberController(custRepo, membersRepo).getMembers(custId);
    }

    // Create Member
    // POST: /api/pathfinder/customers/{customerId}/members/
    public ResponseEntity<String> customersCustIdMembersPost(@ApiParam(value = "Customer Identifier", required = true) @PathVariable("custId") String custId,
                                                             @ApiParam(value = "Member Details") @Valid @RequestBody MemberType body) {
        return new MemberController(custRepo, membersRepo).createMember(custId, body);
    }

    // Get Member
    // GET: /api/pathfinder/customers/{customerId}/members/{memberId}
    public ResponseEntity<MemberType> customersCustIdMembersMemberIdGet(@ApiParam(value = "Customer Identifier", required = true) @PathVariable("custId") String custId,
                                                                        @ApiParam(value = "Member Identifier", required = true) @PathVariable("memberId") String memberId) {
        return new MemberController(custRepo, membersRepo).getMember(custId, memberId);
    }

    // Update Member
    // POST: /api/pathfinder/customers/{customerId}/members/{memberId}
    public ResponseEntity<String> customersCustIdMembersMemberIdPost(@ApiParam(value = "Customer Identifier", required = true) @PathVariable("custId") String custId,
                                                                     @ApiParam(value = "Member Identifier", required = true) @PathVariable("memberId") String memberId,
                                                                     @ApiParam(value = "Member Details") @Valid @RequestBody MemberType body) {
        return new MemberController(custRepo, membersRepo).updateMember(custId, memberId, body);
    }

    // Delete Member(s)
    // POST: /customers/{custId}/members/
    public ResponseEntity<String> customersCustIdMembersDelete(@ApiParam(value = "Customer Identifier", required = true) @PathVariable("custId") String custId,
                                                               @ApiParam(value = "Target member IDs") @Valid @RequestBody IdentifierList body) {
        return new MemberController(custRepo, membersRepo).deleteMembers(custId, body);
    }


    interface QuestionParser<T> {
        void parse(T result, String name, String answerOrdinal, String answerRating, String answerText, String questionText);
    }

    // Non-Swagger api - report page content
    @RequestMapping(value = "/customers/{custId}/report", method = GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @CrossOrigin
    public String getReport(@PathVariable("custId") String custId) throws IOException {
        log.debug("getReport for custID {}", custId);

        class Risk {
            @Getter
            @Setter
            String q;
            @Getter
            @Setter
            String a;
            @Getter
            @Setter
            String apps;

            public Risk(String q, String a, String apps) {
                this.q = q;
                this.a = a;
                this.apps = apps;
            }

            public void addOffendingApp(String app) {
                apps = Joiner.on(", ").join(apps, app);
            }
        }
        class Report {
            Map<String, Double> s;
            List<Risk> risks;

            public Map<String, Double> getAssessmentSummary() {
                if (null == s) s = new HashMap<String, Double>();
                return s;
            }

            public List<Risk> getRisks() {
                if (null == risks) risks = new ArrayList<Risk>();
                return risks;
            }
        }

        Report result = new Report();
        Customer customer = custRepo.findOne(custId);

        Map<String, Integer> overallStatusCount = new HashMap<>();
        overallStatusCount.put("GREEN", 0);
        overallStatusCount.put("AMBER", 0);
        overallStatusCount.put("RED", 0);
        int assessmentTotal = 0;
        Map<String, Risk> risks2 = new HashMap<>();

        if (null != customer.getApplications()) {
            for (Applications app : customer.getApplications()) {
                if (null == app.getAssessments()) continue;
                Assessments assessment = app.getAssessments().get(app.getAssessments().size() - 1);

                Map<String, Map<String, String>> questionKeyToText = new QuestionReader<Map<String, Map<String, String>>>().read(new HashMap<>(),
                        SurveyQuestionsJSON,
                        assessment,
                        (result1, name, answerOrdinal, answerRating, answerText, questionText) -> result1.put(name, new MapBuilder<String, String>()
                                .put("questionText", questionText)
                                .put("answerText", answerText)
                                .build()));

                String assessmentOverallStatus = "GREEN";
                int mediumCount = 0;
                for (Entry<String, String> e : assessment.getResults().entrySet()) {
                    // If ANY answers were RED, then the status is RED
                    if (e.getValue().contains("-RED")) {
                        assessmentOverallStatus = "RED";
                        // add the RED item to the risk list and add the app name against the risk
                        String riskQuestionAnswerKey = e.getKey() + e.getValue();
                        if (!risks2.containsKey(riskQuestionAnswerKey)) {
                            String question = questionKeyToText.get(e.getKey()).get("questionText");
                            String answer = questionKeyToText.get(e.getKey()).get("answerText");
                            risks2.put(riskQuestionAnswerKey, new Risk(question, answer, app.getName()));
                        } else {
                            risks2.get(riskQuestionAnswerKey).addOffendingApp(app.getName());
                        }
                    }
                    if (e.getValue().contains("-AMBER"))
                        mediumCount = mediumCount + 1;

                    // If more than 30% of answers were AMBER, then overall rating is AMBER
                    double percentageOfAmbers = (double) mediumCount / (double) assessment.getResults().size();
                    double threshold = 0.3;
                    if ("GREEN".equals(assessmentOverallStatus) && percentageOfAmbers > threshold) {
                        log.debug("getReport():: amber answer percentage is {} which is over the {}% threshold, therefore downgrading to AMBER rating", (percentageOfAmbers * 100), (threshold * 100));
                        assessmentOverallStatus = "AMBER";
                    }
                }
                assessmentTotal = assessmentTotal + 1;
                overallStatusCount.put(assessmentOverallStatus, overallStatusCount.get(assessmentOverallStatus) + 1);
            }
        }

        result.risks = Lists.newArrayList(risks2.values());
        result.getAssessmentSummary().put("Easy", (double) overallStatusCount.get("GREEN"));
        result.getAssessmentSummary().put("Medium", (double) overallStatusCount.get("AMBER"));
        result.getAssessmentSummary().put("Hard", (double) overallStatusCount.get("RED"));
        result.getAssessmentSummary().put("Total", (double) assessmentTotal);
        String output = Json.newObjectMapper(true).writeValueAsString(result);
        log.trace("getReport for custID {} --> {}", custId, output);
        return output;
    }


    // Non-Swagger api - returns the swagger docs
    @RequestMapping(value = "/docs", method = GET, produces = {"application/javascript"})
    public String getDocs() throws IOException {
        return Json.yamlToJson(IOUtils.toString(this.getClass().getClassLoader().getResourceAsStream("swagger/api.yml"), "UTF-8"));
    }


    private String getResourceAsString(InputStream is) throws IOException {
        Validate.notNull(is);
        return IOUtils.toString(is, "UTF-8");
    }

    // Non-Swagger api - returns payload for the assessment summary page
    @RequestMapping(value = "/customers/{customerId}/applications/{appId}/assessments/{assessmentId}/viewAssessmentSummary", method = GET, produces = {APPLICATION_JSON_VALUE})
    public String viewAssessmentSummary(@PathVariable("customerId") String customerId,
                                        @PathVariable("appId") String appId,
                                        @PathVariable("assessmentId") String assessmentId) throws IOException {

        log.debug("viewAssessmentSummary....CID {}, AID {} ASID {}", customerId, appId, assessmentId);

        class ApplicationAssessmentSummary {
            public ApplicationAssessmentSummary(String q, String a, String r) {
                this.question = q;
                this.answer = a;
                this.rating = r;
            }

            @Getter
            @Setter
            private String question;
            @Getter
            @Setter
            private String answer;
            @Getter
            @Setter
            private String rating;
        }

        // Find the assessment in mongo
        Assessments assessment = assmRepo.findOne(assessmentId);
        if (null == assessment) {
            log.error("Unable to find assessment: " + assessmentId);
            return null;
        }

        List<ApplicationAssessmentSummary> result = new QuestionReader<List<ApplicationAssessmentSummary>>().read(new ArrayList<>(),
                SurveyQuestionsJSON,
                assessment,
                (result1, name, answerOrdinal, answerRating, answerText, questionText) -> result1.add(new ApplicationAssessmentSummary(questionText, answerText, answerRating)));

        String output = Json.newObjectMapper(true).writeValueAsString(result);
        log.debug("viewAssessmentSummary....CID {}, AID {} ASID {} -->{}", customerId, appId, assessmentId, output);
        return output;
    }

    /* Uh-oh, Uncle Noel is going to murder me for this one... yes, yes I will convert to Swagger later on, chalk it up on the technical debt board! */
    @RequestMapping(value = "/assessmentResults", method = GET, produces = {"application/javascript"})
    public String getAssessmentResults(HttpServletRequest request) throws IOException {

        String assessmentId = request.getParameter("assessmentId");
        log.debug("getAssessmentResults...{}", assessmentId);
        Assessments assessment = assmRepo.findOne(assessmentId);
        Map<String, Object> result = new HashMap<>();
        result.putAll(assessment.getResults());
        result.put("DEPSINLIST", assessment.getDepsIN());
        result.put("DEPSOUTLIST", assessment.getDepsOUT());
        String output = Json.newObjectMapper(true).writeValueAsString(result);
        log.debug("getAssessmentResults...{} --->{}", assessmentId, output);
        return output;
    }

    @Timed
    public ResponseEntity<ApplicationNames> customersCustIdApplicationsAppIdCopyPost(@ApiParam(value = "", required = true) @PathVariable("custId") String custId,
                                                                                     @ApiParam(value = "", required = true) @PathVariable("appId") String appId,
                                                                                     @ApiParam(value = "Target Application Names") @Valid @RequestBody ApplicationNames body) {
        log.debug("customersCustIdApplicationsAppIdCopyPost....CID {}, APID {} ", custId, appId);
        ApplicationNames appIDS = new ApplicationNames();
        if (body.isEmpty()) {
            log.error("customersCustIdApplicationsAppIdAssessmentsPost....Empty list of target application names");
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }

        try {
            Customer currCust = custRepo.findOne(custId);
            if (currCust == null) {
                log.error("customersCustIdApplicationsAppIdAssessmentsPost....customer not found " + custId);
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }

            Applications currApp = appsRepo.findOne(appId);
            if (currApp == null) {
                log.error("customersCustIdApplicationsAppIdAssessmentsPost....app not found " + appId);
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }

            ApplicationAssessmentReview currReview = currApp.getReview();
            if (currReview == null)
                log.warn("customersCustIdApplicationsAppIdAssessmentsPost....no reviews for app " + appId);


            List<Assessments> currAssessments = currApp.getAssessments();
            if ((currAssessments == null) || (currAssessments.isEmpty()))
                log.warn("customersCustIdApplicationsAppIdAssessmentsPost....no assessments for app " + appId);

            Assessments latestAssessment = currAssessments == null ? null : currAssessments.get(currAssessments.size() - 1);

            body.forEach((appName) -> {
                log.debug("Creating application {}", appName);

                //Create application
                Applications newApp = new Applications();
                newApp.setId(UUID.randomUUID().toString());
                newApp.setName(appName);
                newApp.setDescription(currApp.getDescription());
                newApp.setStereotype(currApp.getStereotype());

                //Copy Assessment (latest only)
                if (latestAssessment != null) {
                    Assessments newAssessment = new Assessments();
                    newAssessment.setId(UUID.randomUUID().toString());
                    newAssessment.setDatetime(latestAssessment.getDatetime());
                    if (!latestAssessment.getDepsIN().isEmpty())
                        newAssessment.setDepsIN(latestAssessment.getDepsIN());
                    if (!latestAssessment.getDepsOUT().isEmpty())
                        newAssessment.setDepsOUT(latestAssessment.getDepsOUT());
                    newAssessment.setResults(latestAssessment.getResults());
                    newAssessment = assmRepo.save(newAssessment);
                    if (newApp.getAssessments() == null) newApp.setAssessments(new ArrayList<>());
                    newApp.getAssessments().add(newAssessment);

                    //Copy review
                    if (currReview != null) {
                        ApplicationAssessmentReview newReview = new ApplicationAssessmentReview(
                                currReview.getReviewDate(),
                                newAssessment,
                                currReview.getReviewDecision(),
                                currReview.getReviewEstimate(),
                                currReview.getReviewNotes(),
                                currReview.getWorkPriority(),
                                currReview.getBusinessPriority());
                        newReview.setId(UUID.randomUUID().toString());
                        newReview = reviewRepository.insert(newReview);
                        newApp.setReview(newReview);
                    }
                }

                newApp = appsRepo.insert(newApp);
                currCust.getApplications().add(newApp);
                appIDS.add(newApp.getId());
            });
            custRepo.save(currCust);

        } catch (Exception ex) {
            log.error("customersCustIdApplicationsAppIdCopyPost...Unable to copy applications for customer ", ex.getMessage(), ex);
        }
        return new ResponseEntity<>(appIDS, HttpStatus.OK);
    }

    @Timed
    public ResponseEntity<AssessmentType> customersCustIdApplicationsAppIdAssessmentsAssessIdGet(@ApiParam(value = "", required = true) @PathVariable("custId") String custId,
                                                                                                 @ApiParam(value = "", required = true) @PathVariable("appId") String appId,
                                                                                                 @ApiParam(value = "", required = true) @PathVariable("assessId") String assessId) {
        log.debug("customersCustIdApplicationsAppIdAssessmentsAssessIdGet....CID {}, APID {}, ASID {}", custId, appId, assessId);

        AssessmentType resp = new AssessmentType();
        try {
            Assessments currAssm = assmRepo.findOne(assessId);
            if (currAssm != null) {
                resp.setDepsIN(currAssm.getDepsIN());
                resp.setDepsOUT(currAssm.getDepsOUT());
                AssessmentResponse tempPayload = new AssessmentResponse();

                for (Object o : currAssm.getResults().entrySet()) {
                    Map.Entry pair = (Map.Entry) o;
                    tempPayload.put((String) pair.getKey(), (String) pair.getValue());
                }
                resp.setPayload(tempPayload);
                return new ResponseEntity<>(resp, HttpStatus.OK);
            } else {
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }
        } catch (Exception ex) {
            log.error("customersCustIdApplicationsAppIdAssessmentsAssessIdGet", ex.getMessage(), ex);
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
    }

    @Timed
    public ResponseEntity<List<String>> customersCustIdApplicationsAppIdAssessmentsGet(@ApiParam(value = "", required = true) @PathVariable("custId") String custId,
                                                                                       @ApiParam(value = "", required = true) @PathVariable("appId") String appId) {
        log.debug("customersCustIdApplicationsAppIdAssessmentsGet....CID {}, APID {}", custId, appId);
        ArrayList<String> results = new ArrayList<>();
        try {
            Applications currApp = appsRepo.findOne(appId);
            try {
                if (currApp != null) {
                    List<Assessments> res = currApp.getAssessments();
                    if ((res != null) && (!res.isEmpty()))
                        for (Assessments x : res) results.add(x.getId());

                    return new ResponseEntity<>(results, HttpStatus.OK);
                }
            } catch (Exception ex) {
                log.error("Unable to get assessments for customer ", ex.getMessage(), ex);
            }
            return new ResponseEntity<>(results, HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            log.error("customersCustIdApplicationsAppIdAssessmentsGet", ex.getMessage(), ex);
        }
        return new ResponseEntity<>(results, HttpStatus.BAD_REQUEST);
    }

    @Timed
    public ResponseEntity<String> customersCustIdApplicationsAppIdAssessmentsPost(@ApiParam(value = "", required = true) @PathVariable("custId") String custId,
                                                                                  @ApiParam(value = "", required = true) @PathVariable("appId") String appId,
                                                                                  @ApiParam(value = "") @Valid @RequestBody AssessmentType body) {
        log.debug("customersCustIdApplicationsAppIdAssessmentsPost....CID {}, APID {} ", custId, appId);

        try {
            if (!custRepo.exists(custId)) {
                log.error("customersCustIdApplicationsAppIdAssessmentsPost....Customer not found {}", appId);
                return new ResponseEntity<>("Customer Not found", HttpStatus.BAD_REQUEST);
            }

            Applications currApp = appsRepo.findOne(appId);
            if (currApp != null) {
                Assessments newitem = new Assessments();
                newitem.setId(UUID.randomUUID().toString());
                newitem.setResults(body.getPayload());
                newitem.setDepsIN(body.getDepsIN());
                newitem.setDepsOUT(body.getDepsOUT());
                newitem.setDatetime(body.getDatetime());
                newitem = assmRepo.insert(newitem);

                List<Assessments> assmList = currApp.getAssessments();
                if (assmList == null) {
                    assmList = new ArrayList<>();
                }
                assmList.add(newitem);
                currApp.setAssessments(assmList);
                appsRepo.save(currApp);
                return new ResponseEntity<>(newitem.getId(), HttpStatus.OK);
            } else {
                log.error("customersCustIdApplicationsAppIdAssessmentsPost....app not found {}", appId);
                return new ResponseEntity<>("Application not found", HttpStatus.BAD_REQUEST);
            }
        } catch (Exception ex) {
            log.error("customersCustIdApplicationsAppIdAssessmentsPost Unable to create applications for customer ", ex.getMessage(), ex);
        }
        return new ResponseEntity<>("Unable to create assessment", HttpStatus.BAD_REQUEST);
    }

    private AssessmentResponse populateCustomAssessmentFields(String custom, List<Assessments> assList, AssessmentResponse r) {
        if (r == null) r = new AssessmentResponse();

        if (assList != null && assList.size() <= 1) {
            Assessments a = assList.get(assList.size() - 1);
            for (String f : custom.split(",")) {
                if (a.getResults().containsKey(f)) r.put(f, a.getResults().get(f));
            }
        }
        return r;
    }

    private AssessmentResponse populateCustomCustomerFields(String custom, Customer c, AssessmentResponse r) {
        if (r == null) r = new AssessmentResponse();
        for (String f : custom.split(",")) {
            if (f.contains("customer.")) {
                if (f.equalsIgnoreCase("customer.name")) {
                    r.put("customer.name", c.getName());
                } else if (f.equalsIgnoreCase("customer.id")) {
                    r.put("customer.id", c.getId());
                } else
                    log.error("Skipping/Unable to find custom field: " + f);
            }
        }
        return r;
    }

//    private AssessmentResponse populateCustomerFields(AssessmentResponse map, Customer customer, String field){
//      try{
//
//        }
//
//        Map<String, Object> getters=Arrays.asList(
//            Introspector.getBeanInfo(customer.getClass(), Object.class)
//                             .getPropertyDescriptors()
//        )
//        .stream()
//        .filter(pd ->Objects.nonNull(pd.getReadMethod()))
//        .collect(Collectors.toMap(
//                PropertyDescriptor::getName,
//                pd -> {
//                    try {
//                        return pd.getReadMethod().invoke(customer);
//                    } catch (Exception e) {
//                       return null;
//                    }
//                }));
//
//
//        log.debug("GETTING "+"get"+StringUtils.capitalize(field));
//        log.debug("FOUND {} CUSTOMER GETTERS "+getters.size());
//        for(Entry<String, Object> e:getters.entrySet()){
//          log.debug("CUSTOMER PROPERTIES: {} = {}", e.getKey(), e.getValue());
//        }
//
//        log.debug("ADDING CUSTOMER FIELD {}={}", field, getters.get(field));
//
//        map.put(field, (String)getters.get("get"+StringUtils.capitalize(field)));
//      }catch (IntrospectionException e){
//        e.printStackTrace();
//      }
//      return map;
//    }


    // Get Application
    // GET: /customers/{customerId}/applications/{applicationId}
    //
    @Timed
    public ResponseEntity<ApplicationType> customersCustIdApplicationsAppIdGet(@ApiParam(value = "Customer Identifier", required = true) @PathVariable("custId") String custId,
                                                                               @ApiParam(value = "Application Identifier", required = true) @PathVariable("appId") String appId,
                                                                               @RequestParam(value = "custom", required = false) String custom) {
        log.debug("customersCustIdApplicationsAppIdGet cid {} app {}", custId, appId);
        ApplicationType response = new ApplicationType();
        //TODO : Check customer exists and owns application as well as application
        Customer customer = custRepo.findOne(custId);
        if (customer == null) {
            log.error("customersCustIdApplicationsAppIdGet....customer not found {}", custId);
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        try {
            Applications application = appsRepo.findOne(appId);
            response.setDescription(application.getDescription());
            response.setName(application.getName());
            response.setOwner(application.getOwner());
            response.setId(appId);
            if (application.getReview() != null)
                response.setReview(application.getReview().getId());
            if ((application.getStereotype() != null) && (!application.getStereotype().isEmpty())) {
                response.setStereotype(ApplicationType.StereotypeEnum.fromValue(application.getStereotype()));
            }

            // custom fields parsing/injection
            if (null != custom) {
                AssessmentResponse customFieldMap = new AssessmentResponse();
                for (String f : custom.split(",")) {
                    if (f.indexOf(".") <= 0) continue;
                    String entity = f.substring(0, f.indexOf("."));
                    String field = f.substring(f.indexOf(".") + 1);
                    if (entity.equals("customer")) {
                        for (PropertyDescriptor pd : Introspector.getBeanInfo(Customer.class).getPropertyDescriptors()) {
                            if (pd.getReadMethod() != null && pd.getReadMethod().getName().equals("get" + StringUtils.capitalize(field)) && !"class".equals(pd.getName())) {
                                Object value = pd.getReadMethod().invoke(customer);
                                if (value instanceof String) {
                                    log.debug("Adding custom customer field:: {}={}", field, (String) pd.getReadMethod().invoke(customer));
                                    customFieldMap.put(entity + "." + field, (String) value);
                                }
                            }
                        }
                    } else if (entity.equals("assessment")) {
                        if (application.getAssessments() != null && application.getAssessments().size() > 0) {
                            Assessments latestAssessment = application.getAssessments().get(application.getAssessments().size() - 1);
                            log.debug("Adding customer assessment field:: {}={}", field, latestAssessment.getResults().get(field));

//                            customFieldMap.put(entity + "." + field, latestAssessment.getResults().get(field));
                            customFieldMap.put(entity + "." + field, extractNotes(latestAssessment.getResults()));
                        }

                    }
                }
                response.setCustomFields(customFieldMap);
            }
        } catch (Exception ex) {
            log.error("Unable to get applications for customer ", ex.getMessage(), ex);
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    private String extractNotes(HashMap<String, String> results) {
        String optionalNotes = results.entrySet().stream()
                .filter(x -> x.getKey().contains("NOTESONPAGE"))
                .map(x -> x.getValue() + ".<br>")
                .collect(Collectors.joining());
        return optionalNotes;
    }


    @Override
    @Timed
    public ResponseEntity<String> customersCustIdApplicationsDelete(@ApiParam(value = "", required = true) @PathVariable("custId") String custId,
                                                                    @ApiParam(value = "Target Application Names") @Valid @RequestBody ApplicationNames body) {
        log.info("customersCustIdApplicationsDelete....CID {}, apps {}", custId, body.toString());
        try {
            Customer currCust = custRepo.findOne(custId);
            if (currCust == null) {
                log.error("customersCustIdApplicationsDelete....customer not found {}", custId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            for (String appId : body) {
                try {
                    Applications delApp = appsRepo.findOne(appId);
                    if (delApp == null) {
                        log.error("customersCustIdApplicationsDelete....application not found {}", appId);
                        return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
                    }

                    List<Applications> currApps = currCust.getApplications();
                    List<Applications> newApps = new ArrayList<>();
                    boolean appFound = false;

                    for (Applications x : currApps) {
                        if (x.getId().equalsIgnoreCase(appId)) {
                            appFound = true;
                        } else {
                            newApps.add(x);
                        }
                    }

                    if (!appFound) {
                        log.error("customersCustIdApplicationsAppIdDelete....application not found {} in customer list {}", appId, custId);
                        return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
                    }

                    currCust.setApplications(newApps);
                    custRepo.save(currCust);

                    if (delApp.getReview() != null)
                        reviewRepository.delete(delApp.getReview());

                    if (delApp.getAssessments() != null)
                        assmRepo.delete(delApp.getAssessments());

                    appsRepo.delete(appId);
                } catch (Exception ex) {
                    log.error("Error while deleting application [" + appId + "]", ex.getMessage(), ex);
                    return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
        } catch (Exception ex) {
            log.error("Error with customer [" + custId + "] while deleting application(s)", ex.getMessage(), ex);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>(HttpStatus.OK);
    }


    // Get Applications
    // GET: /api/pathfinder/customers/{customerId}/applications/
    @Override
    @Timed
    public ResponseEntity<List<ApplicationType>> customersCustIdApplicationsGet(@ApiParam(value = "", required = true) @PathVariable("custId") String custId,
                                                                                @ApiParam(value = "TARGETS,DEPENDENCIES,PROFILES") @RequestParam(value = "apptype", required = false) String apptype,
                                                                                @ApiParam(value = "app id to exclude") @RequestParam(value = "exclude", required = false) String exclude) {
        log.info("customersCustIdApplicationsGet....CID {}", custId);
        ArrayList<ApplicationType> response = new ArrayList<>();
        try {
            Customer customer = custRepo.findOne(custId);
            if (customer == null) {
                log.error("customersCustIdApplicationsGet....[" + custId + "] customer not found");
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }
            List<Applications> resp = customer.getApplications();
            if (isAuthorizedFor(customer)) {
                if ((resp != null) && (!resp.isEmpty())) {
                    for (Applications x : resp) {

                        if (null != exclude && x.getId().equals(exclude)) continue;

                        ApplicationType app = new ApplicationType();
                        app.setName(x.getName());
                        app.setId(x.getId());
                        if (x.getReview() != null) app.setReview(x.getReview().getId());
                        app.setDescription(x.getDescription());
                        app.setOwner(x.getOwner());

                        if (x.getStereotype() != null) {
                            if (apptype != null) {
                                switch (apptype) {
                                    case "TARGETS":  //Explicit targets
                                        if (x.getStereotype().equals(ApplicationType.StereotypeEnum.TARGETAPP.toString())) {
                                            app.setStereotype(ApplicationType.StereotypeEnum.fromValue(x.getStereotype()));
                                            response.add(app);
                                        }
                                        break;
                                    case "DEPENDENCIES": //Dependencies - Everything but Profiles
                                        if (!x.getStereotype().equals(ApplicationType.StereotypeEnum.DEPENDENCY.toString())) {
                                            app.setStereotype(ApplicationType.StereotypeEnum.fromValue(x.getStereotype()));
                                            response.add(app);
                                        }
                                        break;
                                    case "PROFILES": //Explicit Profiles
                                        if (x.getStereotype().equals(ApplicationType.StereotypeEnum.PROFILE.toString())) {
                                            app.setStereotype(ApplicationType.StereotypeEnum.fromValue(x.getStereotype()));
                                            response.add(app);
                                        }
                                        break;
                                    default:
                                        break;
                                }
                            } else {
                                //maintain backward compatibility with initial api when no apptype is passed - All Dependencies
                                if (!x.getStereotype().equals(ApplicationType.StereotypeEnum.PROFILE.toString())) {
                                    app.setStereotype(ApplicationType.StereotypeEnum.fromValue(x.getStereotype()));
                                    response.add(app);
                                }
                            }
                        } else {
                            response.add(app);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.error("Unable to list applications for customer ", ex.getMessage(), ex);
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    // Create Application
    // POST: /api/pathfinder/customers/{customerId}/applications/
    @Timed
    public ResponseEntity<String> customersCustIdApplicationsPost(@ApiParam(value = "Customer Identifier", required = true) @PathVariable("custId") String custId,
                                                                  @ApiParam(value = "Application Definition") @Valid @RequestBody ApplicationType body) {
        log.debug("customersCustIdApplicationsPost....CID {}", custId);
        return createOrUpdateApplication(custId, null, body);
    }

    // Update application
    // POST: /api/pathfinder/customers/{customerId}/applications/{applicationId}
    public ResponseEntity<String> customersCustIdApplicationsAppIdPost(@ApiParam(value = "Customer Identifier", required = true) @PathVariable("custId") String custId,
                                                                       @ApiParam(value = "Application Identifier", required = true) @PathVariable("appId") String appId,
                                                                       @ApiParam(value = "Application Definition") @Valid @RequestBody ApplicationType body) {
        log.debug("customersCustIdApplicationsAppIdPost....CID {}, APID {}", custId, appId);
        return createOrUpdateApplication(custId, appId, body);
    }

    public ResponseEntity<String> createOrUpdateApplication(String custId, String appId, ApplicationType body) {
        Customer myCust = custRepo.findOne(custId);
        if (myCust == null) {
            return new ResponseEntity<>(custId, HttpStatus.BAD_REQUEST);
        } else {

            Applications app;
            if (appId == null) {
                app = new Applications();
                app.setId(UUID.randomUUID().toString());
            } else {
                app = appsRepo.findOne(appId);
            }

            app.setName(body.getName());
            app.setDescription(body.getDescription());
            app.setOwner(body.getOwner());

            if (body.getStereotype() == null) {
                log.warn("createOrUpdateApplication....application stereotype missing ");
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            } else {
                app.setStereotype(body.getStereotype().toString());
            }
            app = appsRepo.save(app);

            if (appId == null) {
                List<Applications> appList = myCust.getApplications();
                if (appList == null) {
                    appList = new ArrayList<Applications>();
                }
                appList.add(app);
                myCust.setApplications(appList);
                custRepo.save(myCust);
            }
            return new ResponseEntity<>(app.getId(), HttpStatus.OK);
        }
    }


    // Get Customer
    // GET: /api/pathfinder/customers/{customerId}
    @Timed
    public ResponseEntity<CustomerType> customersCustIdGet(@ApiParam(value = "Customer Identifier", required = true) @PathVariable("custId") String custId) {
        log.debug("customersCustIdGet....{}", custId);
        Customer myCust = custRepo.findOne(custId);
        if (myCust == null) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        } else {
            CustomerType resp = new CustomerType();
            resp.setCustomerName(myCust.getName());
            resp.setCustomerId(myCust.getId());
            resp.setCustomerDescription(myCust.getDescription());
            resp.setCustomerSize(myCust.getSize());
            resp.setCustomerVertical(myCust.getVertical());
            resp.setCustomerRTILink(myCust.getRtilink());
            resp.setCustomerAssessor(myCust.getAssessor());
            return new ResponseEntity<>(resp, HttpStatus.OK);
        }
    }

    // Create Customer
    // POST: /api/pathfinder/customers/
    @Timed
    public ResponseEntity<String> customersPost(@ApiParam(value = "") @Valid @RequestBody CustomerType body) {
        log.debug("customersPost....{}", body);
        return createOrUpdateCustomer(null, body);
    }

    // Update Customer
    // POST: /api/pathfinder/customers/{custId}
    public ResponseEntity<String> customersCustIdPost(@ApiParam(value = "Customer Identifier", required = true) @PathVariable("custId") String custId,
                                                      @ApiParam(value = "") @Valid @RequestBody CustomerType body) {
        log.debug("customersCustIdPost....CID {}, {}", custId, body);
        return createOrUpdateCustomer(custId, body);
    }

    public ResponseEntity<String> createOrUpdateCustomer(String custId, CustomerType body) {
        log.debug("createOrUpdateCustomer....CID {}, {}", custId, body);
        Customer myCust;
        if (custId == null) {
            myCust = new Customer();
            myCust.setId(UUID.randomUUID().toString());

            // check customer doesnt already exist with the same name
            Customer example = new Customer();
            example.setName(body.getCustomerName());
            long count = custRepo.count(Example.of(example));
            if (count > 0) {
                log.error("Customer already exists with name {}", body.getCustomerName());
                return new ResponseEntity<>("Customer already exists with name " + body.getCustomerName(), HttpStatus.BAD_REQUEST);
            }

        } else {
            myCust = custRepo.findOne(custId);
        }

        myCust.setName(body.getCustomerName());
        myCust.setDescription(body.getCustomerDescription());
        myCust.setVertical(body.getCustomerVertical());
        myCust.setSize(body.getCustomerSize());
        myCust.setRtilink(body.getCustomerRTILink());
        myCust.setVertical(body.getCustomerVertical());
        myCust.setAssessor(body.getCustomerAssessor());
        try {
            myCust = custRepo.save(myCust);
        } catch (Exception ex) {
            log.error("Unable to Create customer ", ex.getMessage(), ex);
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(myCust.getId(), HttpStatus.OK);
    }

    // Get Customers
    // GET: /api/pathfinder/customers/
    @Timed
    public ResponseEntity<List<CustomerType>> customersGet() {
        log.debug("customersGet....");
        ArrayList<CustomerType> response = new ArrayList<>();

        log.debug("customersGet....findallStart");
        List<Customer> customers = custRepo.findAll();
        log.debug("customersGet....findallStop");

        if (customers == null) {
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        } else {
            customers.stream().filter(c -> isAuthorizedFor(c)).forEach(customer -> {
                // then add the customer to the response
                CustomerType resp = new CustomerType();
                resp.setCustomerId(customer.getId());
                resp.setCustomerName(customer.getName());
                resp.setCustomerDescription(customer.getDescription());
                resp.setCustomerSize(customer.getSize());
                resp.setCustomerVertical(customer.getVertical());
                resp.setCustomerAssessor(customer.getAssessor());
                resp.setCustomerRTILink(customer.getRtilink());
                resp.setCustomerPercentageComplete(0);
                resp.setCustomerAppCount(customer.getApplications() == null ? 0 : customer.getApplications().size());
                resp.setCustomerMemberCount(customer.getMembers() == null ? 0 : customer.getMembers().size());

                List<Applications> appList =
                        Optional.ofNullable(customer.getApplications())
                                .map(Collection::stream)
                                .orElseGet(Stream::empty)
                                .filter(ap -> ap.getStereotype().equalsIgnoreCase(ApplicationType.StereotypeEnum.TARGETAPP.toString()))
                                .collect(Collectors.toList());

                if (!appList.isEmpty()) {
                    int totalAssessible = appList.size();
                    AtomicInteger assessedCount = new AtomicInteger();
                    AtomicInteger reviewedCount = new AtomicInteger();

                    appList.forEach(app -> {
                        ApplicationAssessmentReview review = app.getReview();
                        // if review is null, then it's not been reviewed
                        reviewedCount.addAndGet((review != null ? 1 : 0));

                        List<Assessments> assmList = app.getAssessments();
                        if ((assmList != null) && (!assmList.isEmpty())) {
                            assessedCount.addAndGet(1);
                        }
                    });

                    // reviewedCount + assessedCount / potential total (ie. total * 2)
                    BigDecimal percentageComplete = new BigDecimal(100 * (double) (assessedCount.get() + reviewedCount.get()) / (double) (totalAssessible * 2));
                    percentageComplete.setScale(0, BigDecimal.ROUND_DOWN);
                    resp.setCustomerPercentageComplete(percentageComplete.intValue());// a merge of assessed & reviewed
                }
                response.add(resp);
            });
        }
        log.debug("customersGet....done");
        return new ResponseEntity<>(response, HttpStatus.OK);
    }


    @Timed
    public ResponseEntity<Void> customersDelete(@ApiParam(value = "Target Customer Names") @Valid @RequestBody ApplicationNames body) {
        log.debug("customersDelete....{}", body);

        for (String customerId : body) {
            log.info("customersDelete: deleting customer {}", customerId);
            try {

                Customer c = custRepo.findOne(customerId);
                if (c == null) {
                    log.error("customersDelete....customer not found {}", customerId);
                    return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
                }

                log.debug("customersDelete: customer [{}] had " + (c.getApplications() != null ? c.getApplications().size() : 0) + " apps", customerId);
                if (null != c.getApplications()) {
                    for (Applications app : c.getApplications()) {
                        if (app == null) continue;  // TODO: Remove this MAT!!!!
                        log.debug("customersDelete: deleting customer [{}] application [{}]", customerId, app.getId());

                        if (null != app.getAssessments()) {
                            for (Assessments ass : app.getAssessments()) {
                                log.debug("customersDelete: deleting customer [{}] application [{}] assessment [{}]", customerId, app.getId(), ass.getId());

                                assmRepo.delete(ass.getId());
                            }
                        }

                        if (null != app.getReview()) {
                            reviewRepository.delete(app.getReview().getId());
                        }

                        appsRepo.delete(app.getId());
                    }
                }

                custRepo.delete(customerId);

            } catch (Exception e) {
                log.error("Error deleting customer [" + customerId + "] ", e.getMessage(), e);
                return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Timed
    public ResponseEntity<AssessmentProcessType> customersCustIdApplicationsAppIdAssessmentsAssessIdProcessGet(
            @ApiParam(value = "", required = true) @PathVariable("custId") String custId,
            @ApiParam(value = "", required = true) @PathVariable("appId") String appId,
            @ApiParam(value = "", required = true) @PathVariable("assessId") String assessId) {
        log.debug("customersCustIdApplicationsAppIdAssessmentsAssessIdProcessGet....CID {}, APID {}, ASID {}", custId, appId, assessId);

        AssessmentProcessType resp = new AssessmentProcessType();
        List<AssessmentProcessQuestionResultsType> assessResults = new ArrayList<>();

        try {

            Assessments currAssm = assmRepo.findOne(assessId);
            if (currAssm == null) {
                return new ResponseEntity<>(resp, HttpStatus.BAD_REQUEST);
            }

            resp.setAssessResults(assessResults);
            resp.setAssmentNotes(currAssm.getResults().get("NOTES"));
            resp.setDependenciesIN(currAssm.getDepsIN());
            resp.setDependenciesOUT(currAssm.getDepsOUT());
            resp.setBusinessPriority(currAssm.getResults().get("BUSPRIORITY"));

        } catch (Exception ex) {
            log.error("Error while processing assessment", ex.getMessage(), ex);
            return new ResponseEntity<>(resp, HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>(resp, HttpStatus.OK);
    }

    @Timed
    public ResponseEntity<String> customersCustIdApplicationsAppIdReviewPost(@ApiParam(value = "", required = true) @PathVariable("custId") String custId,
                                                                             @ApiParam(value = "", required = true) @PathVariable("appId") String appId,
                                                                             @ApiParam(value = "Application Definition") @Valid @RequestBody ReviewType body) {
        log.debug("customersCustIdApplicationsAppIdReviewPost....CID {}, APID {}", custId, appId);
        try {
            Applications app = appsRepo.findOne(appId);
            if (app == null) {
                log.error("Error while processing review - Unable to find application with id {}", appId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            Assessments assm = assmRepo.findOne(body.getAssessmentId());
            if (assm == null) {
                log.error("Error while processing review - Unable to find assessment with id {}", body.getAssessmentId());
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            ApplicationAssessmentReview reviewData = new ApplicationAssessmentReview(
                    Long.toString(System.currentTimeMillis()),
                    assm,
                    body.getReviewDecision().toString(),
                    body.getWorkEffort().toString(),
                    body.getReviewNotes(),
                    body.getWorkPriority(),
                    body.getBusinessPriority());

            if (app.getReview() != null) {
                reviewData.setId(app.getReview().getId());
            } else {
                reviewData.setId(UUID.randomUUID().toString());
            }


            reviewData = reviewRepository.save(reviewData);
            app.setReview(reviewData);
            appsRepo.save(app);

            return new ResponseEntity<>(reviewData.getId(), HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Error while processing review", ex.getMessage(), ex);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Timed
    public ResponseEntity<ReviewType> customersCustIdApplicationsAppIdReviewReviewIdGet(@ApiParam(value = "", required = true) @PathVariable("custId") String custId,
                                                                                        @ApiParam(value = "", required = true) @PathVariable("appId") String appId,
                                                                                        @ApiParam(value = "", required = true) @PathVariable("reviewId") String reviewId) {
        log.debug("customersCustIdApplicationsAppIdReviewReviewIdGet....CID {}, APID {}, RVID {}", custId, appId, reviewId);
        try {

            Customer customer = custRepo.findOne(custId);
            if (customer == null) {
                log.error("Error while retrieving review....customer not found {}", custId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            Applications app = appsRepo.findOne(appId);
            if (app == null) {
                log.error("Error while retrieving review - Unable to find application with id {}", appId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            if (app.getReview() == null) {
                log.error("Error while retrieving review - no review associated with application {}", reviewId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            ApplicationAssessmentReview review = reviewRepository.findOne(app.getReview().getId());

            if (review == null || !review.getId().equals(reviewId)) {
                log.error("Error while retrieving review - Unable to find review for application {}", appId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            ReviewType result = new ReviewType();
            result.setAssessmentId(review.getAssessments().getId());
            result.setReviewDecision(ReviewType.ReviewDecisionEnum.fromValue(review.getReviewDecision()));
            result.setReviewNotes(review.getReviewNotes());
            result.setWorkEffort(ReviewType.WorkEffortEnum.fromValue(review.getReviewEstimate()));
            result.setReviewTimestamp(review.getReviewDate());
            result.setWorkPriority(review.getWorkPriority());
            result.setBusinessPriority(review.getBusinessPriority());
            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Error while processing review", ex.getMessage(), ex);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Timed
    public ResponseEntity<List<ReviewType>> customersCustIdReviewsGet(@ApiParam(value = "Customer Identifier", required = true) @PathVariable("custId") String custId) {
        log.debug("customersCustIdReviewsGet....CID {}", custId);
        ArrayList<ReviewType> resp = new ArrayList<>();

        try {
            Customer currCust = custRepo.findOne(custId);
            if (currCust == null) {
                log.error("customersCustIdReviewsGet....customer not found {}", custId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            List<Applications> appList = currCust.getApplications();
            if ((appList == null) || (appList.isEmpty())) {
                log.error("customersCustIdReviewsGet....no applications for customer {}", custId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            for (Applications x : appList) {
                ApplicationAssessmentReview tmpRev = x.getReview();
                if (tmpRev != null) {
                    ReviewType newRev = new ReviewType();
                    newRev.setBusinessPriority(tmpRev.getBusinessPriority());
                    newRev.setWorkPriority(tmpRev.getWorkPriority());
                    newRev.setReviewTimestamp(tmpRev.getReviewDate());
                    newRev.setWorkEffort(ReviewType.WorkEffortEnum.fromValue(tmpRev.getReviewEstimate()));
                    newRev.setReviewNotes(tmpRev.getReviewNotes());
                    newRev.setReviewDecision(ReviewType.ReviewDecisionEnum.fromValue(tmpRev.getReviewDecision()));
                    newRev.setAssessmentId(x.getName());
                    resp.add(newRev);
                }
            }

        } catch (Exception ex) {
            log.error("Error while processing review", ex.getMessage(), ex);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return new ResponseEntity<>(resp, HttpStatus.OK);
    }

    @Timed
    public ResponseEntity<Void> customersCustIdApplicationsAppIdDelete(@ApiParam(value = "Customer Identifier", required = true) @PathVariable("custId") String custId,
                                                                       @ApiParam(value = "Application Identifier", required = true) @PathVariable("appId") String appId) {
        log.debug("customersCustIdApplicationsAppIdDelete {} {}", custId, appId);
        try {
            Customer currCust = custRepo.findOne(custId);
            if (currCust == null) {
                log.error("customersCustIdApplicationsAppIdDelete....customer not found {}", custId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            Applications delApp = appsRepo.findOne(appId);
            if (delApp == null) {
                log.error("customersCustIdApplicationsAppIdDelete....application not found {}", appId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }
            List<Applications> currApps = currCust.getApplications();
            List<Applications> newApps = new ArrayList<>();
            boolean appFound = false;
            for (Applications x : currApps) {
                if (x.getId().equalsIgnoreCase(appId)) {
                    appFound = true;
                } else {
                    newApps.add(x);
                }
            }

            if (!appFound) {
                log.error("customersCustIdApplicationsAppIdDelete....application not found {} in customer list {}", appId, custId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            currCust.setApplications(newApps);
            custRepo.save(currCust);

            if (delApp.getReview() != null)
                reviewRepository.delete(delApp.getReview());

            if (delApp.getAssessments() != null)
                assmRepo.delete(delApp.getAssessments());

            appsRepo.delete(appId);

        } catch (Exception ex) {
            log.error("Error while deleting application", ex.getMessage(), ex);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Timed
    public ResponseEntity<Void> customersCustIdApplicationsAppIdReviewReviewIdDelete(@ApiParam(value = "", required = true) @PathVariable("custId") String custId,
                                                                                     @ApiParam(value = "", required = true) @PathVariable("appId") String appId,
                                                                                     @ApiParam(value = "", required = true) @PathVariable("reviewId") String reviewId) {
        log.debug("customersCustIdApplicationsAppIdReviewReviewIdDelete {} {} {}", custId, appId, reviewId);
        try {
            Customer currCust = custRepo.findOne(custId);
            if (currCust == null) {
                log.error("customersCustIdApplicationsAppIdReviewReviewIdDelete....customer not found {}", custId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }
            Applications currApp = appsRepo.findOne(appId);
            if (currApp == null) {
                log.error("customersCustIdApplicationsAppIdReviewReviewIdDelete....application not found {}", appId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            if (currApp.getReview().getId().equalsIgnoreCase(reviewId)) {
                currApp.setReview(null);
                appsRepo.save(currApp);
                reviewRepository.delete(reviewId);
            } else {
                log.error("customersCustIdApplicationsAppIdReviewReviewIdDelete....review {} not found for application", reviewId, appId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }
        } catch (Exception ex) {
            log.error("Error while deleting review", ex.getMessage(), ex);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Timed
    public ResponseEntity<Void> customersCustIdApplicationsAppIdAssessmentsAssessIdDelete(@ApiParam(value = "", required = true) @PathVariable("custId") String custId,
                                                                                          @ApiParam(value = "", required = true) @PathVariable("appId") String appId,
                                                                                          @ApiParam(value = "", required = true) @PathVariable("assessId") String assessId) {
        log.debug("customersCustIdApplicationsAppIdAssessmentsAssessIdDelete {} {} {}", custId, appId, assessId);
        try {
            Customer currCust = custRepo.findOne(custId);
            if (currCust == null) {
                log.error("customersCustIdApplicationsAppIdAssessmentsAssessIdDelete....customer not found {}", custId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            Applications currApp = appsRepo.findOne(appId);
            if (currApp == null) {
                log.error("customersCustIdApplicationsAppIdAssessmentsAssessIdDelete....application not found {}", appId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            List<Assessments> assmList = currApp.getAssessments();

            if ((assmList == null) || (assmList.isEmpty())) {
                log.error("customersCustIdApplicationsAppIdAssessmentsAssessIdDelete....assessment list is null for app {}", appId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            boolean assmFound = false;
            List<Assessments> newAssmLst = new ArrayList<>();

            for (Assessments x : assmList) {
                if (x.getId().equalsIgnoreCase(assessId)) {
                    assmFound = true;
                } else {
                    newAssmLst.add(x);
                }
            }

            if (assmFound) {
                assmRepo.delete(assessId);
                currApp.setAssessments(newAssmLst);
                appsRepo.save(currApp);
            } else {
                log.error("customersCustIdApplicationsAppIdAssessmentsAssessIdDelete....assessment not found for app {}", appId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }
        } catch (Exception ex) {
            log.error("Error while deleting assessment", ex.getMessage(), ex);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Timed
    public ResponseEntity<Void> customersCustIdDelete(@ApiParam(value = "Customer Identifier", required = true) @PathVariable("custId") String custId) {
        log.debug("customersCustIdDelete {}", custId);
        try {
            Customer currCust = custRepo.findOne(custId);
            if (currCust == null) {
                log.error("customersCustIdDelete....customer not found {}", custId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }
            if ((currCust.getApplications() != null) && (!currCust.getApplications().isEmpty())) {
                log.error("Customer {} has applications...not deleting", custId);
                return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
            }
            custRepo.delete(custId);
        } catch (Exception ex) {
            log.error("Error while deleting customer", ex.getMessage(), ex);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>(HttpStatus.OK);
    }


    private Integer calculateConfidence(Assessments assessment, ApplicationAssessmentReview review) throws IOException {
        double confidence = 0;

        Map<String, Integer> weightMap = new MapBuilder<String, Integer>()
                .put("RED", 1)
                .put("UNKNOWN", 700)
                .put("AMBER", 800)
                .put("GREEN", 1000)
                .build();


        // Get the questions, answers, ratings etc...
        Map<String, Map<String, String>> questionInfo = new QuestionReader<Map<String, Map<String, String>>>().read(new HashMap<String, Map<String, String>>(),
                SurveyQuestionsJSON,
                assessment,
                (result, name, answerOrdinal, answerRating, answerText, questionText) -> result.put(name, new MapBuilder<String, String>()
                        .put("answerRating", answerRating)
                        .build()));

        List<String> ratings = new ArrayList<>();
        for (Entry<String, String> qa : assessment.getResults().entrySet()) {
            if (null != questionInfo.get(qa.getKey())) { //ie, an answered question with a missing question definition
                String rating = questionInfo.get(qa.getKey()).get("answerRating");
                ratings.add(rating);
            }
        }
        Collections.sort(ratings,
                (o1, o2) -> "RED".equals(o1) ? -1 : 0
        );

        int redCount = Collections.frequency(ratings, "RED");
        int amberCount = Collections.frequency(ratings, "AMBER");

        double adjuster = 1;

        if (redCount > 0) adjuster = adjuster * Math.pow(0.5, redCount);
        if (amberCount > 0) adjuster = adjuster * Math.pow(0.98, amberCount);

        for (String rating : ratings) {
            if ("RED".equals(rating)) confidence = confidence * 0.6;
            if ("AMBER".equals(rating)) confidence = confidence * 0.95;


            int questionWeight = 1; //not implemented yet
//        if ("RED".equals(rating)) adjuster=adjuster/2;
            confidence += weightMap.get(rating) * adjuster * questionWeight;
        }
        int answerCount = ratings.size();
        int maxConfidence = weightMap.get("GREEN") * answerCount;
        BigDecimal result = new BigDecimal(((double) confidence / (double) maxConfidence) * 100);
        result.setScale(0, BigDecimal.ROUND_DOWN);
        return result.intValue();
    }

    // Get assessment summary data for UI's assessment summary screen
    // GET: /api/pathfinder/customers/{customerId}/applicationAssessmentSummary
    @Timed
    public ResponseEntity<List<ApplicationSummaryType>> customersCustIdApplicationAssessmentSummaryGet(@ApiParam(value = "Customer Identifier", required = true) @PathVariable("custId") String custId) {
        log.debug("customersCustIdApplicationAssessmentSummaryGet {}", custId);
        List<ApplicationSummaryType> resp = new ArrayList<>();
        try {
            Customer currCust = custRepo.findOne(custId);
            if (currCust == null) {
                log.error("customersCustIdApplicationAssessmentSummaryGet....customer not found {}", custId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }
            List<Applications> applications = currCust.getApplications();

            if ((applications == null) || (applications.isEmpty())) {
                log.info("customersCustIdApplicationAssessmentSummaryGet Customer {} has no applications...", custId);
            } else {
                for (Applications app : applications) {
                    if (app == null) continue; //TODO Mat fix this!!!
                    if (app.getStereotype().equals(ApplicationType.StereotypeEnum.TARGETAPP.toString())) {
                        ApplicationSummaryType item = new ApplicationSummaryType();
                        item.setId(app.getId());
                        item.setName(app.getName());
                        ApplicationAssessmentReview review = app.getReview();
                        List<Assessments> assmList = app.getAssessments();
                        Assessments assessment = null;
                        if ((assmList != null) && (!assmList.isEmpty())) assessment = assmList.get(assmList.size() - 1);
                        item.assessed(assessment != null);
                        if (item.getAssessed()) {
                            item.setLatestAssessmentId(assessment.getId());
                            item.setIncompleteAnswersCount(Collections.frequency(assessment.getResults().values(), "0-UNKNOWN"));
                            item.setCompleteAnswersCount(assessment.getResults().size() - item.getIncompleteAnswersCount());
                            item.setOutboundDeps(assessment.getDepsOUT());
                        }
                        if (review != null) {
                            item.setReviewDate(review.getReviewDate());
                            item.setDecision(review.getReviewDecision());
                            item.setWorkEffort(review.getReviewEstimate());
                            item.setWorkPriority(Integer.parseInt(null == review.getWorkPriority() ? "0" : review.getWorkPriority()));
                            item.setBusinessPriority(Integer.parseInt(null == review.getBusinessPriority() ? "0" : review.getBusinessPriority()));

                        }
                        if (item.getAssessed() && review != null) {
                            item.setConfidence(!item.getAssessed() ? 0 : calculateConfidence(assessment, review));
                        }
                        resp.add(item);
                    }
                }
            }
        } catch (Exception ex) {
            log.error("Error while processing customersCustIdApplicationAssessmentSummaryGet", ex.getMessage(), ex);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>(resp, HttpStatus.OK);
    }

    @Timed
    public ResponseEntity<ApplicationAssessmentProgressType> customersCustIdApplicationAssessmentProgressGet(@ApiParam(value = "Customer Identifier", required = true) @PathVariable("custId") String custId) {

        log.debug("customersCustIdApplicationAssessmentProgressGet {}", custId);
        ApplicationAssessmentProgressType resp = new ApplicationAssessmentProgressType();
        int appCount = 0, assessedCount = 0, reviewedCount = 0;

        try {
            Customer currCust = custRepo.findOne(custId);
            if (currCust == null) {
                log.error("customersCustIdApplicationAssessmentProgressGet....customer not found {}", custId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            List<Applications> apps = currCust.getApplications();

            if ((apps == null) || (apps.isEmpty())) {
                log.warn("Customer {} has no applications...", custId);
            } else {

                appCount = currCust.getApplications().size();

                for (Applications currApp : apps) {
                    reviewedCount = reviewedCount + (currApp.getReview() != null ? 1 : 0);
                    assessedCount = assessedCount + ((currApp.getAssessments() != null) && (!currApp.getAssessments().isEmpty()) ? 1 : 0);
                }
            }
        } catch (Exception ex) {
            log.error("Error while processing customersCustIdApplicationAssessmentProgressGet", ex.getMessage(), ex);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        resp.setAppcount(appCount);
        resp.setAssessed(assessedCount);
        resp.setReviewed(reviewedCount);
        return new ResponseEntity<>(resp, HttpStatus.OK);
    }


    public ResponseEntity<DependenciesListType> customersCustIdDependencyTreeGet(@ApiParam(value = "Customer Identifier", required = true) @PathVariable("custId") String custId,
                                                                                 @NotNull @ApiParam(value = "Specify the depedency direction from the applications persepctive NORTHBOUND = incoming to, SOUTHBOUND = outgoing from",
                                                                                         required = true, allowableValues = "NORTHBOUND, SOUTHBOUND") @RequestParam(value = "direction", required = true) String direction) {
        log.debug("customersCustIdDependencyTreeGet CID {}, DIR {}", custId, direction);
        DependenciesListType respDeps = new DependenciesListType();

        try {
            Customer currCust = custRepo.findOne(custId);
            if (currCust == null) {
                log.error("customersCustIdDependencyTreeGet....customer not found {}", custId);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }
            List<Applications> apps = currCust.getApplications();
            if ((apps == null) || (apps.isEmpty())) {
                log.warn("Customer {} has no applications...", custId);
            } else {
                for (Applications currApp : apps) {
                    List<Assessments> currAssmList = currApp.getAssessments();

                    if (currAssmList == null || currAssmList.size() == 0) {
                        log.info("Application {} has no assessments...", currApp.getId());
                    } else {

                        String assmID = currAssmList.get(currAssmList.size() - 1).getId();
                        Assessments currAssm = assmRepo.findOne(assmID);
                        List<String> depList;

                        if (direction.equals("NORTHBOUND")) {
                            depList = currAssm.getDepsIN();
                            if (depList.size() == 0) {
                                log.info("Application {} has no NorthBound dependencies...", currApp.getId());
                            } else {
                                for (String currDep : depList) {
                                    DepsPairType dep = new DepsPairType();
                                    dep.to(currApp.getId());
                                    dep.from(currDep);
                                    respDeps.addDepsListItem(dep);
                                }
                            }
                        } else {
                            depList = currAssm.getDepsOUT();
                            if (depList.size() == 0) {
                                log.info("Application {} has no SouthBound dependencies...", currApp.getId());
                            } else {
                                for (String currDep : depList) {
                                    DepsPairType dep = new DepsPairType();
                                    dep.from(currApp.getId());
                                    dep.to(currDep);
                                    respDeps.addDepsListItem(dep);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.error("Error while processing customersCustIdDependencyTreeGet", ex.getMessage(), ex);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>(respDeps, HttpStatus.OK);
    }
}
