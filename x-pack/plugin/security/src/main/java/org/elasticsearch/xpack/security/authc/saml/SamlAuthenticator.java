/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.saml;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.xpack.core.security.authc.RealmConfig;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.EncryptedAssertion;
import org.opensaml.saml.saml2.core.EncryptedAttribute;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.StatusDetail;
import org.opensaml.saml.saml2.core.StatusMessage;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.xmlsec.encryption.support.DecryptionException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import static org.elasticsearch.xpack.security.authc.saml.SamlUtils.samlException;
import static org.opensaml.saml.saml2.core.SubjectConfirmation.METHOD_BEARER;

/**
 * Processes the IdP's SAML Response for our AuthnRequest, validates it, and extracts the relevant properties.
 */
class SamlAuthenticator extends SamlRequestHandler {

    private static final String RESPONSE_TAG_NAME = "Response";

    SamlAuthenticator(RealmConfig realmConfig,
                      Clock clock,
                      IdpConfiguration idp,
                      SpConfiguration sp,
                      TimeValue maxSkew) {
        super(realmConfig, clock, idp, sp, maxSkew);
    }

    /**
     * Processes the provided SAML response within the provided token and, if valid, extracts the relevant attributes from it.
     *
     * @throws org.elasticsearch.ElasticsearchSecurityException If the SAML is invalid for this realm/configuration
     */
    SamlAttributes authenticate(SamlToken token) {
        final Element root = parseSamlMessage(token.getContent());
        if (RESPONSE_TAG_NAME.equals(root.getLocalName()) && SAML_NAMESPACE.equals(root.getNamespaceURI())) {
            try {
                return authenticateResponse(root, token.getAllowedSamlRequestIds());
            } catch (ElasticsearchSecurityException e) {
                logger.trace("Rejecting SAML response {} because {}", SamlUtils.toString(root), e.getMessage());
                throw e;
            }
        } else {
            throw samlException("SAML content [{}] should have a root element of Namespace=[{}] Tag=[{}]",
                    root, SAML_NAMESPACE, RESPONSE_TAG_NAME);
        }
    }

    private SamlAttributes authenticateResponse(Element element, Collection<String> allowedSamlRequestIds) {
        final Response response = buildXmlObject(element, Response.class);
        if (response == null) {
            throw samlException("Cannot convert element {} into Response object", element);
        }
        if (logger.isTraceEnabled()) {
            logger.trace(SamlUtils.describeSamlObject(response));
        }
        final boolean requireSignedAssertions;
        if (response.isSigned()) {
            validateSignature(response.getSignature());
            requireSignedAssertions = false;
        } else {
            requireSignedAssertions = true;
        }

        if (Strings.hasText(response.getInResponseTo()) && allowedSamlRequestIds.contains(response.getInResponseTo()) == false) {
            logger.debug("The SAML Response with ID {} is unsolicited. A user might have used a stale URL or the Identity Provider " +
                    "incorrectly populates the InResponseTo attribute", response.getID());
            throw samlException("SAML content is in-response-to {} but expected one of {} ",
                    response.getInResponseTo(), allowedSamlRequestIds);
        }

        final Status status = response.getStatus();
        if (status == null || status.getStatusCode() == null) {
            throw samlException("SAML Response has no status code");
        }
        if (isSuccess(status) == false) {
            throw samlException("SAML Response is not a 'success' response: Code={} Message={} Detail={}",
                    status.getStatusCode().getValue(), getMessage(status), getDetail(status));
        }
        checkIssuer(response.getIssuer(), response);
        checkResponseDestination(response);

        Tuple<Assertion, List<Attribute>> details = extractDetails(response, allowedSamlRequestIds, requireSignedAssertions);
        final Assertion assertion = details.v1();
        final SamlNameId nameId = SamlNameId.forSubject(assertion.getSubject());
        final String session = getSessionIndex(assertion);
        final List<SamlAttributes.SamlAttribute> attributes = details.v2().stream()
                .map(SamlAttributes.SamlAttribute::new)
                .collect(Collectors.toList());
        if (logger.isTraceEnabled()) {
            StringBuilder sb = new StringBuilder();
            sb.append("The SAML Assertion contained the following attributes: \n");
            for (SamlAttributes.SamlAttribute attr : attributes) {
                sb.append(attr).append("\n");
            }
            logger.trace(sb.toString());
        }
        if (attributes.isEmpty() && nameId == null) {
            logger.debug("The Attribute Statements of SAML Response with ID {} contained no attributes and the SAML Assertion Subject did" +
                    "not contain a SAML NameID. Please verify that the Identity Provider configuration with regards to attribute " +
                    "release is correct. ", response.getID());
            throw samlException("Could not process any SAML attributes in {}", response.getElementQName());
        }

        return new SamlAttributes(nameId, session, attributes);
    }

    private String getMessage(Status status) {
        final StatusMessage sm = status.getStatusMessage();
        return sm == null ? null : sm.getMessage();
    }

    private String getDetail(Status status) {
        final StatusDetail sd = status.getStatusDetail();
        return sd == null ? null : SamlUtils.toString(sd.getDOM());
    }

    private boolean isSuccess(Status status) {
        return status.getStatusCode().getValue().equals(StatusCode.SUCCESS);
    }

    private String getSessionIndex(Assertion assertion) {
        return assertion.getAuthnStatements().stream().map(as -> as.getSessionIndex()).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private void checkResponseDestination(Response response) {
        final String asc = getSpConfiguration().getAscUrl();
        if (asc.equals(response.getDestination()) == false) {
            if (response.isSigned() || Strings.hasText(response.getDestination())) {
                throw samlException("SAML response " + response.getID() + " is for destination " + response.getDestination()
                    + " but this realm uses " + asc);
            }
        }
    }

    private Tuple<Assertion, List<Attribute>> extractDetails(Response response, Collection<String> allowedSamlRequestIds,
                                                             boolean requireSignedAssertions) {
        final int assertionCount = response.getAssertions().size() + response.getEncryptedAssertions().size();
        if (assertionCount > 1) {
            throw samlException("Expecting only 1 assertion, but response contains multiple (" + assertionCount + ")");
        }
        for (Assertion assertion : response.getAssertions()) {
            return new Tuple<>(assertion, processAssertion(assertion, requireSignedAssertions, allowedSamlRequestIds));
        }
        for (EncryptedAssertion encrypted : response.getEncryptedAssertions()) {
            Assertion assertion = decrypt(encrypted);
            moveToNewDocument(assertion);
            assertion.getDOM().setIdAttribute("ID", true);
            return new Tuple<>(assertion, processAssertion(assertion, requireSignedAssertions, allowedSamlRequestIds));
        }
        throw samlException("No assertions found in SAML response");
    }

    private void moveToNewDocument(XMLObject xmlObject) {
        final Element element = xmlObject.getDOM();
        final Document doc = element.getOwnerDocument().getImplementation().createDocument(null, null, null);
        doc.adoptNode(element);
        doc.appendChild(element);
    }

    private Assertion decrypt(EncryptedAssertion encrypted) {
        if (decrypter == null) {
            throw samlException("SAML assertion [" + text(encrypted, 32) + "] is encrypted, but no decryption key is available");
        }
        try {
            return decrypter.decrypt(encrypted);
        } catch (DecryptionException e) {
            logger.debug(() -> new ParameterizedMessage("Failed to decrypt SAML assertion [{}] with [{}]",
                    text(encrypted, 512), describe(getSpConfiguration().getEncryptionCredentials())), e);
            throw samlException("Failed to decrypt SAML assertion " + text(encrypted, 32), e);
        }
    }

    private List<Attribute> processAssertion(Assertion assertion, boolean requireSignature, Collection<String> allowedSamlRequestIds) {
        if (logger.isTraceEnabled()) {
            logger.trace("(Possibly decrypted) Assertion: {}", SamlUtils.samlObjectToString(assertion));
            logger.trace(SamlUtils.describeSamlObject(assertion));
        }
        // Do not further process unsigned Assertions
        if (assertion.isSigned()) {
            validateSignature(assertion.getSignature());
        } else if (requireSignature) {
            throw samlException("Assertion [{}] is not signed, but a signature is required", assertion.getElementQName());
        }

        checkConditions(assertion.getConditions());
        checkIssuer(assertion.getIssuer(), assertion);
        checkSubject(assertion.getSubject(), assertion, allowedSamlRequestIds);

        List<Attribute> attributes = new ArrayList<>();
        for (AttributeStatement statement : assertion.getAttributeStatements()) {
            logger.trace("SAML AttributeStatement has [{}] attributes and [{}] encrypted attributes",
                    statement.getAttributes().size(), statement.getEncryptedAttributes().size());
            attributes.addAll(statement.getAttributes());
            for (EncryptedAttribute enc : statement.getEncryptedAttributes()) {
                final Attribute attribute = decrypt(enc);
                if (attribute != null) {
                    logger.trace("Successfully decrypted attribute: {}" + SamlUtils.samlObjectToString(attribute));
                    attributes.add(attribute);
                }
            }
        }
        return attributes;
    }

    private Attribute decrypt(EncryptedAttribute encrypted) {
        if (decrypter == null) {
            logger.info("SAML message has encrypted attribute [" + text(encrypted, 32) + "], but no encryption key has been configured");
            return null;
        }
        try {
            return decrypter.decrypt(encrypted);
        } catch (DecryptionException e) {
            logger.info("Failed to decrypt SAML attribute " + text(encrypted, 32), e);
            return null;
        }
    }

    private void checkConditions(Conditions conditions) {
        if (conditions != null) {
            if (logger.isTraceEnabled()) {
                logger.trace("SAML Assertion was intended for the following Service providers: {}",
                        conditions.getAudienceRestrictions().stream().map(r -> text(r, 32))
                                .collect(Collectors.joining(" | ")));
                logger.trace("SAML Assertion is only valid between: " + conditions.getNotBefore() + " and " + conditions.getNotOnOrAfter());
            }
            checkAudienceRestrictions(conditions.getAudienceRestrictions());
            checkLifetimeRestrictions(conditions);
        }
    }

    private void checkSubject(Subject assertionSubject, XMLObject parent, Collection<String> allowedSamlRequestIds) {

        if (assertionSubject == null) {
            throw samlException("SAML Assertion ({}) has no Subject", text(parent, 16));
        }
        final List<SubjectConfirmationData> confirmationData = assertionSubject.getSubjectConfirmations().stream()
                .filter(data -> data.getMethod().equals(METHOD_BEARER))
                .map(SubjectConfirmation::getSubjectConfirmationData).filter(Objects::nonNull).collect(Collectors.toList());
        if (confirmationData.size() != 1) {
            throw samlException("SAML Assertion subject contains {} bearer SubjectConfirmation, while exactly one was expected.",
                    confirmationData.size());
        }
        if (logger.isTraceEnabled()) {
            logger.trace("SAML Assertion Subject Confirmation intended recipient is: " + confirmationData.get(0).getRecipient());
            logger.trace("SAML Assertion Subject Confirmation is only valid before: " + confirmationData.get(0).getNotOnOrAfter());
            logger.trace("SAML Assertion Subject Confirmation is in response to: " + confirmationData.get(0).getInResponseTo());
        }
        checkRecipient(confirmationData.get(0));
        checkLifetimeRestrictions(confirmationData.get(0));
        checkInResponseTo(confirmationData.get(0), allowedSamlRequestIds);
    }

    private void checkRecipient(SubjectConfirmationData subjectConfirmationData) {
        final SpConfiguration sp = getSpConfiguration();
        if (sp.getAscUrl().equals(subjectConfirmationData.getRecipient()) == false) {
            throw samlException("SAML Assertion SubjectConfirmationData Recipient {} does not match expected value {}",
                    subjectConfirmationData.getRecipient(), sp.getAscUrl());
        }
    }

    private void checkInResponseTo(SubjectConfirmationData subjectConfirmationData, Collection<String> allowedSamlRequestIds) {
        // Allow for IdP initiated SSO where InResponseTo MUST be missing
        if (Strings.hasText(subjectConfirmationData.getInResponseTo())
                && allowedSamlRequestIds.contains(subjectConfirmationData.getInResponseTo()) == false) {
            throw samlException("SAML Assertion SubjectConfirmationData is in-response-to {} but expected one of {} ",
                    subjectConfirmationData.getInResponseTo(), allowedSamlRequestIds);
        }
    }

    private void checkAudienceRestrictions(List<AudienceRestriction> restrictions) {
        final String spEntityId = this.getSpConfiguration().getEntityId();
        final Predicate<AudienceRestriction> predicate = ar ->
                ar.getAudiences().stream().map(Audience::getAudienceURI).anyMatch(spEntityId::equals);
        if (restrictions.stream().allMatch(predicate) == false) {
            throw samlException("Conditions [{}] do not match required audience [{}]",
                    restrictions.stream().map(r -> text(r, 32)).collect(Collectors.joining(" | ")), getSpConfiguration().getEntityId());
        }
    }

    private void checkLifetimeRestrictions(Conditions conditions) {
        // In order to compensate for clock skew we construct 2 alternate realities
        //  - a "future now" that is now + the maximum skew we will tolerate. Essentially "if our clock is 2min slow, what time is it now?"
        //  - a "past now" that is now - the maximum skew we will tolerate. Essentially "if our clock is 2min fast, what time is it now?"
        final Instant now = now();
        final Instant futureNow = now.plusMillis(maxSkewInMillis());
        final Instant pastNow = now.minusMillis(maxSkewInMillis());
        if (conditions.getNotBefore() != null && futureNow.isBefore(toInstant(conditions.getNotBefore()))) {
            throw samlException("Rejecting SAML assertion because [{}] is before [{}]", futureNow, conditions.getNotBefore());
        }
        if (conditions.getNotOnOrAfter() != null && pastNow.isBefore(toInstant(conditions.getNotOnOrAfter())) == false) {
            throw samlException("Rejecting SAML assertion because [{}] is on/after [{}]", pastNow, conditions.getNotOnOrAfter());
        }
    }

    private void checkLifetimeRestrictions(SubjectConfirmationData subjectConfirmationData) {
        validateNotOnOrAfter(subjectConfirmationData.getNotOnOrAfter());
    }
}
