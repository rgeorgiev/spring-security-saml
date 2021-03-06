/* Copyright 2009 Vladimir Schäfer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.saml.websso;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.opensaml.Configuration;
import org.opensaml.common.SAMLException;
import org.opensaml.common.SAMLObjectBuilder;
import org.opensaml.saml2.core.*;
import org.opensaml.saml2.metadata.AssertionConsumerService;
import org.opensaml.xml.XMLObjectBuilderFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.saml.context.SAMLContextProvider;
import org.springframework.security.saml.context.SAMLMessageContext;
import org.springframework.security.saml.SAMLTestBase;
import org.springframework.security.saml.key.KeyManager;
import org.springframework.security.saml.metadata.MetadataManager;
import org.springframework.security.saml.processor.SAMLProcessor;
import org.springframework.security.saml.storage.SAMLMessageStorage;

import javax.servlet.http.HttpServletRequest;

import java.util.Arrays;

import static org.easymock.EasyMock.*;

/**
 * @author Vladimir Schäfer
 */
public class WebSSOProfileConsumerImplTest extends SAMLTestBase {

    ApplicationContext context;
    WebSSOProfileConsumerImpl profile;
    SAMLMessageStorage storage;
    SAMLMessageContext messageContext;
    MetadataManager manager;
    XMLObjectBuilderFactory builderFactory;
    WebSSOProfileTestHelper helper;
    KeyManager resolver;
    SAMLProcessor processor;
    SAMLContextProvider contextProvider;

    @Before
    public void initialize() throws Exception {

        String resName = "/" + getClass().getName().replace('.', '/') + ".xml";
        context = new ClassPathXmlApplicationContext(resName);
        storage = createMock(SAMLMessageStorage.class);
        manager = context.getBean("metadata", MetadataManager.class);
        resolver = context.getBean("keyManager", KeyManager.class);
        processor = context.getBean("processor", SAMLProcessor.class);
        profile = new WebSSOProfileConsumerImpl(processor, manager);
        contextProvider = context.getBean("contextProvider", SAMLContextProvider.class);
        builderFactory = Configuration.getBuilderFactory();

        HttpServletRequest request = createMock(HttpServletRequest.class);
        expect(request.getContextPath()).andReturn("/").anyTimes();
        expect(request.getAttribute("javax.servlet.request.X509Certificate")).andReturn(null).anyTimes();
        replay(request);

        AssertionConsumerService assertionConsumerService = ((SAMLObjectBuilder<AssertionConsumerService>) builderFactory.getBuilder(AssertionConsumerService.DEFAULT_ELEMENT_NAME)).buildObject();
        assertionConsumerService.setLocation("testLocation");

        messageContext = contextProvider.getLocalEntity(request, null);
        messageContext.setLocalEntityEndpoint(assertionConsumerService);
        verify(request);

        helper = new WebSSOProfileTestHelper(builderFactory);
        messageContext.setInboundMessage(helper.getValidResponse());

    }

    /**
     * Verifies that in case SAML response object is missing from the context the processing fails.
     *
     * @throws Exception error
     */
    @Test(expected = SAMLException.class)
    public void testMissingResponse() throws Exception {
        messageContext.setInboundMessage(null);
        profile.processAuthenticationResponse(messageContext);
    }

    /**
     * Verifies that in case the response object is not of expected type the processing will fail.
     *
     * @throws Exception error
     */
    @Test(expected = SAMLException.class)
    public void testInvalidResponseObject() throws Exception {
        SAMLObjectBuilder<AuthnRequest> builder = (SAMLObjectBuilder<AuthnRequest>) builderFactory.getBuilder(AuthnRequest.DEFAULT_ELEMENT_NAME);
        AuthnRequest authnRequest = builder.buildObject();
        messageContext.setInboundMessage(authnRequest);
        profile.processAuthenticationResponse(messageContext);
    }

    /**
     * Verifies that default authNStatement - currently created and expiring in three hours is accepted
     * by verification method.
     *
     * @throws Exception error
     */
    @Test
    public void testDefaultAuthNStatementPasses() throws Exception {
        AuthnStatement statement = helper.getValidAuthStatement();
        profile.verifyAuthenticationStatement(statement, null, messageContext);
    }

    /**
     * Verifies that in case the session expiry time is in the past the statement is rejected.
     *
     * @throws Exception error
     */
    @Test(expected = CredentialsExpiredException.class)
    public void testAuthNStatementWithExpiredSessionTime() throws Exception {
        AuthnStatement statement = helper.getValidAuthStatement();
        DateTime past = new DateTime().minusMinutes(10);
        statement.setSessionNotOnOrAfter(past);
        profile.verifyAuthenticationStatement(statement, null, messageContext);
    }

    /**
     * Verifies that authnContext with exact comparison passes once one of the classRefs is satisifed.
     *
     * @throws Exception error
     */
    @Test
    public void testAuthnExactComparison() throws Exception {
        RequestedAuthnContext requestedAuthnContext = helper.getRequestedAuthnContext(AuthnContextComparisonTypeEnumeration.EXACT, Arrays.asList("test", "test2"));
        AuthnContext authnContext = helper.getAuthnContext(helper.getClassRef("test2"), null);
        profile.verifyAuthnContext(requestedAuthnContext, authnContext, null);
    }

    /**
     * Verifies that authnContext with exact comparison fails when none is satisfied.
     *
     * @throws Exception error
     */
    @Test(expected = InsufficientAuthenticationException.class)
    public void testAuthnExactComparison_none() throws Exception {
        RequestedAuthnContext requestedAuthnContext = helper.getRequestedAuthnContext(AuthnContextComparisonTypeEnumeration.EXACT, Arrays.asList("test", "test2"));
        AuthnContext authnContext = helper.getAuthnContext(helper.getClassRef("test5"), null);
        profile.verifyAuthnContext(requestedAuthnContext, authnContext, null);
    }

    /**
     * Verifies that no-conditions when no audience is required pass.
     *
     * @throws Exception error
     */
    @Test
    public void testCondition_empty() throws Exception {
        SAMLObjectBuilder<Conditions> builder = (SAMLObjectBuilder<Conditions>) builderFactory.getBuilder(Conditions.DEFAULT_ELEMENT_NAME);
        Conditions conditions = builder.buildObject();
        profile.verifyAssertionConditions(conditions, messageContext, false);
    }

    /**
     * Verifies that no-conditions when audience is required fail.
     *
     * @throws Exception error
     */
    @Test(expected = SAMLException.class)
    public void testCondition_empty_audienceRequired() throws Exception {
        SAMLObjectBuilder<Conditions> builder = (SAMLObjectBuilder<Conditions>) builderFactory.getBuilder(Conditions.DEFAULT_ELEMENT_NAME);
        Conditions conditions = builder.buildObject();
        profile.verifyAssertionConditions(conditions, messageContext, true);
    }

    /**
     * Verifies that audience restriction passes when localEntityId matches.
     *
     * @throws Exception error
     */
    @Test
    public void testCondition_Audience_pass() throws Exception {
        SAMLObjectBuilder<Conditions> builder = (SAMLObjectBuilder<Conditions>) builderFactory.getBuilder(Conditions.DEFAULT_ELEMENT_NAME);
        Conditions conditions = builder.buildObject();
        conditions.getConditions().add(helper.getAudienceRestriction(messageContext.getLocalEntityId()));
        profile.verifyAssertionConditions(conditions, messageContext, true);
    }

    /**
     * Verifies that audience restriction fails when uri doesn't match
     *
     * @throws Exception error
     */
    @Test(expected = SAMLException.class)
    public void testCondition_Audience_fail() throws Exception {
        SAMLObjectBuilder<Conditions> builder = (SAMLObjectBuilder<Conditions>) builderFactory.getBuilder(Conditions.DEFAULT_ELEMENT_NAME);
        Conditions conditions = builder.buildObject();
        conditions.getConditions().add(helper.getAudienceRestriction("wrong"));
        profile.verifyAssertionConditions(conditions, messageContext, true);
    }

    /**
     * Verifies that OneTimeUse condition will make the assertion rejected.
     *
     * @throws Exception error
     */
    @Test(expected = SAMLException.class)
    public void testCondition_OneTimeUse() throws Exception {
        SAMLObjectBuilder<Conditions> builder = (SAMLObjectBuilder<Conditions>) builderFactory.getBuilder(Conditions.DEFAULT_ELEMENT_NAME);
        Conditions conditions = builder.buildObject();
        conditions.getConditions().add(helper.getAudienceRestriction(messageContext.getLocalEntityId()));
        conditions.getConditions().add(((SAMLObjectBuilder<OneTimeUse>) builderFactory.getBuilder(OneTimeUse.DEFAULT_ELEMENT_NAME)).buildObject());
        profile.verifyAssertionConditions(conditions, messageContext, true);
    }

    /**
     * Verifies that subject confirmation with all data passes. Also verifies that time-skew is being used on the notOnOrAfter.
     *
     * @throws Exception error
     */
    @Test
    public void verifySubject() throws Exception {

        SubjectConfirmationData subjectConfirmationData = ((SAMLObjectBuilder<SubjectConfirmationData>) builderFactory.getBuilder(SubjectConfirmationData.DEFAULT_ELEMENT_NAME)).buildObject();
        subjectConfirmationData.setNotOnOrAfter(new DateTime().minusSeconds(10));
        subjectConfirmationData.setRecipient("testLocation");

        SAMLObjectBuilder<SubjectConfirmation> subjectConfirmationBuilder = (SAMLObjectBuilder<SubjectConfirmation>) builderFactory.getBuilder(SubjectConfirmation.DEFAULT_ELEMENT_NAME);
        SubjectConfirmation subjectConfirmation = subjectConfirmationBuilder.buildObject();
        subjectConfirmation.setMethod(SubjectConfirmation.METHOD_BEARER);
        subjectConfirmation.setSubjectConfirmationData(subjectConfirmationData);

        SAMLObjectBuilder<Subject> subjectBuilder = (SAMLObjectBuilder<Subject>) builderFactory.getBuilder(Subject.DEFAULT_ELEMENT_NAME);
        Subject subject = subjectBuilder.buildObject();
        subject.getSubjectConfirmations().add(subjectConfirmation);

        profile.verifySubject(subject, null, messageContext);

    }

    /**
     * Verifies that once notOnOrAfter is exceeded the confirmation is rejected.
     *
     * @throws Exception error
     */
    @Test(expected = SAMLException.class)
    public void verifySubjectNotOnOrAfterExceeded() throws Exception {

        SubjectConfirmationData subjectConfirmationData = ((SAMLObjectBuilder<SubjectConfirmationData>) builderFactory.getBuilder(SubjectConfirmationData.DEFAULT_ELEMENT_NAME)).buildObject();
        subjectConfirmationData.setNotOnOrAfter(new DateTime().minusSeconds(70));
        subjectConfirmationData.setRecipient("testLocation");

        SAMLObjectBuilder<SubjectConfirmation> subjectConfirmationBuilder = (SAMLObjectBuilder<SubjectConfirmation>) builderFactory.getBuilder(SubjectConfirmation.DEFAULT_ELEMENT_NAME);
        SubjectConfirmation subjectConfirmation = subjectConfirmationBuilder.buildObject();
        subjectConfirmation.setMethod(SubjectConfirmation.METHOD_BEARER);
        subjectConfirmation.setSubjectConfirmationData(subjectConfirmationData);

        SAMLObjectBuilder<Subject> subjectBuilder = (SAMLObjectBuilder<Subject>) builderFactory.getBuilder(Subject.DEFAULT_ELEMENT_NAME);
        Subject subject = subjectBuilder.buildObject();
        subject.getSubjectConfirmations().add(subjectConfirmation);

        profile.verifySubject(subject, null, messageContext);

    }

    /**
     * Verifies that subject confirmation with all doesn't pass when notOnOrAfter is missing.
     *
     * @throws Exception error
     */
    @Test(expected = SAMLException.class)
    public void verifySubjectMissingNotOnOrAfter() throws Exception {

        SubjectConfirmationData subjectConfirmationData = ((SAMLObjectBuilder<SubjectConfirmationData>) builderFactory.getBuilder(SubjectConfirmationData.DEFAULT_ELEMENT_NAME)).buildObject();
        subjectConfirmationData.setRecipient("testLocation");

        SAMLObjectBuilder<SubjectConfirmation> subjectConfirmationBuilder = (SAMLObjectBuilder<SubjectConfirmation>) builderFactory.getBuilder(SubjectConfirmation.DEFAULT_ELEMENT_NAME);
        SubjectConfirmation subjectConfirmation = subjectConfirmationBuilder.buildObject();
        subjectConfirmation.setMethod(SubjectConfirmation.METHOD_BEARER);
        subjectConfirmation.setSubjectConfirmationData(subjectConfirmationData);

        SAMLObjectBuilder<Subject> subjectBuilder = (SAMLObjectBuilder<Subject>) builderFactory.getBuilder(Subject.DEFAULT_ELEMENT_NAME);
        Subject subject = subjectBuilder.buildObject();
        subject.getSubjectConfirmations().add(subjectConfirmation);

        profile.verifySubject(subject, null, messageContext);

    }

    private void verifyMock() {
        verify(storage);
    }

    private void replyMock() {
        replay(storage);
    }
}

