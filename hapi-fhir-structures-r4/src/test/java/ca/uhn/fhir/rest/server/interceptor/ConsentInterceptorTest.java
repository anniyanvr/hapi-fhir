package ca.uhn.fhir.rest.server.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.api.BundleInclusionRule;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.annotation.RequiredParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.server.FifoMemoryPagingProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.interceptor.auth.SearchNarrowingInterceptorTest;
import ca.uhn.fhir.rest.server.interceptor.consent.ConsentInterceptor;
import ca.uhn.fhir.rest.server.interceptor.consent.ConsentOperationStatusEnum;
import ca.uhn.fhir.rest.server.interceptor.consent.ConsentOutcome;
import ca.uhn.fhir.rest.server.interceptor.consent.IConsentService;
import ca.uhn.fhir.rest.server.provider.HashMapResourceProvider;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.test.utilities.JettyUtil;
import com.google.common.base.Charsets;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.hl7.fhir.instance.model.api.IBaseParameters;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ConsentInterceptorTest {

	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(ConsentInterceptorTest.class);
	private static CloseableHttpClient ourClient;
	private static FhirContext ourCtx = FhirContext.forR4();
	private static int ourPort;
	private static RestfulServer ourServlet;
	private static Server ourServer;
	private static DummyPatientResourceProvider ourPatientProvider;
	private static IGenericClient ourFhirClient;
	private static DummySystemProvider ourSystemProvider;

	@Mock
	private IConsentService myConsentSvc;
	private ConsentInterceptor myInterceptor;
	@Captor
	private ArgumentCaptor<BaseServerResponseException> myExceptionCaptor;

	@AfterEach
	public void after() {
		ourServlet.unregisterInterceptor(myInterceptor);
	}

	@BeforeEach
	public void before() {
		myInterceptor = new ConsentInterceptor(myConsentSvc);
		ourServlet.registerInterceptor(myInterceptor);
		ourServlet.setPagingProvider(new FifoMemoryPagingProvider(10));
		ourPatientProvider.clear();
	}

	@Test
	public void testOutcomeSuccess() throws IOException {
		Patient patientA = new Patient();
		patientA.setId("Patient/A");
		patientA.setActive(true);
		patientA.addName().setFamily("FAMILY").addGiven("GIVEN");
		patientA.addIdentifier().setSystem("SYSTEM").setValue("VALUEA");
		ourPatientProvider.store(patientA);

		Patient patientB = new Patient();
		patientB.setId("Patient/B");
		patientB.setActive(true);
		patientB.addName().setFamily("FAMILY").addGiven("GIVEN");
		patientB.addIdentifier().setSystem("SYSTEM").setValue("VALUEB");
		ourPatientProvider.store(patientB);

		when(myConsentSvc.startOperation(any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.canSeeResource(any(), any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.willSeeResource(any(), any(), any())).thenReturn(ConsentOutcome.PROCEED);

		HttpGet httpGet = new HttpGet("http://localhost:" + ourPort + "/Patient");

		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(200, status.getStatusLine().getStatusCode());
			String responseContent = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseContent);
		}

		verify(myConsentSvc, times(1)).completeOperationSuccess(any(), any());
		verify(myConsentSvc, times(0)).completeOperationFailure(any(), any(), any());
	}

	@Test
	public void testTotalModeIgnoredForConsentQueries() throws IOException {
		Patient patientA = new Patient();
		patientA.setId("Patient/A");
		patientA.setActive(true);
		patientA.addName().setFamily("FAMILY").addGiven("GIVEN");
		patientA.addIdentifier().setSystem("SYSTEM").setValue("VALUEA");
		ourPatientProvider.store(patientA);

		Patient patientB = new Patient();
		patientB.setId("Patient/B");
		patientB.setActive(true);
		patientB.addName().setFamily("FAMILY").addGiven("GIVEN");
		patientB.addIdentifier().setSystem("SYSTEM").setValue("VALUEB");
		ourPatientProvider.store(patientB);

		HttpGet httpGet;

		httpGet = new HttpGet("http://localhost:" + ourPort + "/Patient?_total=accurate");
		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(400, status.getStatusLine().getStatusCode());
			String responseContent = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseContent);
			assertThat(responseContent, containsString(Msg.code(2037) + "_total=accurate is not permitted on this server"));
		}

		when(myConsentSvc.startOperation(any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.canSeeResource(any(), any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.willSeeResource(any(), any(), any())).thenReturn(ConsentOutcome.PROCEED);
		httpGet = new HttpGet("http://localhost:" + ourPort + "/Patient?_total=estimated");
		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(200, status.getStatusLine().getStatusCode());
			String responseContent = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseContent);
			assertThat(responseContent, not(containsString("\"total\"")));
		}

		httpGet = new HttpGet("http://localhost:" + ourPort + "/Patient?_total=none");
		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(200, status.getStatusLine().getStatusCode());
			String responseContent = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseContent);
			assertThat(responseContent, not(containsString("\"total\"")));
		}
	}

	@Test
	public void testSummaryModeIgnoredTotalForConsentQueries() throws IOException {
		Patient patientA = new Patient();
		patientA.setId("Patient/A");
		patientA.setActive(true);
		patientA.addName().setFamily("FAMILY").addGiven("GIVEN");
		patientA.addIdentifier().setSystem("SYSTEM").setValue("VALUEA");
		ourPatientProvider.store(patientA);

		Patient patientB = new Patient();
		patientB.setId("Patient/B");
		patientB.setActive(true);
		patientB.addName().setFamily("FAMILY").addGiven("GIVEN");
		patientB.addIdentifier().setSystem("SYSTEM").setValue("VALUEB");
		ourPatientProvider.store(patientB);

		HttpGet httpGet;

		httpGet = new HttpGet("http://localhost:" + ourPort + "/Patient?_summary=count");
		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(400, status.getStatusLine().getStatusCode());
			String responseContent = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseContent);
			assertThat(responseContent, containsString(Msg.code(2038) + "_summary=count is not permitted on this server"));
		}

		when(myConsentSvc.startOperation(any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.canSeeResource(any(), any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.willSeeResource(any(), any(), any())).thenReturn(ConsentOutcome.PROCEED);
		httpGet = new HttpGet("http://localhost:" + ourPort + "/Patient?_summary=data");
		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(200, status.getStatusLine().getStatusCode());
			String responseContent = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseContent);
			assertThat(responseContent, not(containsString("\"total\"")));
		}

		when(myConsentSvc.startOperation(any(), any())).thenReturn(ConsentOutcome.AUTHORIZED);
		httpGet = new HttpGet("http://localhost:" + ourPort + "/Patient?_summary=data");
		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(200, status.getStatusLine().getStatusCode());
			String responseContent = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseContent);
			assertThat(responseContent, containsString("\"total\""));
		}

	}

	@Test
	public void testMetadataCallHasChecksSkipped() throws IOException{
		HttpGet httpGet = new HttpGet("http://localhost:" + ourPort + "/metadata");
		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(200, status.getStatusLine().getStatusCode());
			String responseContent = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseContent);
		}

		httpGet = new HttpGet("http://localhost:" + ourPort + "/$meta");
		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(200, status.getStatusLine().getStatusCode());
			String responseContent = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseContent);
		}

		verify(myConsentSvc, times(0)).canSeeResource(any(), any(), any());
		verify(myConsentSvc, times(0)).willSeeResource(any(), any(), any());
		verify(myConsentSvc, times(0)).startOperation(any(), any());
		verify(myConsentSvc, times(2)).completeOperationSuccess(any(), any());
	}

	@Test
	public void testSearch_SeeResourceAuthorizesOuterBundle() throws IOException {
		ourPatientProvider.store((Patient) new Patient().setActive(true).setId("PTA"));
		ourPatientProvider.store((Patient) new Patient().setActive(false).setId("PTB"));

		when(myConsentSvc.startOperation(any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.canSeeResource(any(RequestDetails.class), any(IBaseResource.class), any())).thenAnswer(t-> ConsentOutcome.PROCEED);
		when(myConsentSvc.willSeeResource(any(RequestDetails.class), any(IBaseResource.class), any())).thenAnswer(t-> ConsentOutcome.AUTHORIZED);

		HttpGet httpGet = new HttpGet("http://localhost:" + ourPort + "/Patient");

		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(200, status.getStatusLine().getStatusCode());
			String responseContent = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseContent);
			assertThat(responseContent, containsString("PTA"));
		}

		verify(myConsentSvc, times(1)).startOperation(any(), any());
		verify(myConsentSvc, times(2)).canSeeResource(any(), any(), any());
		verify(myConsentSvc, times(3)).willSeeResource(any(), any(), any());
		verify(myConsentSvc, times(1)).completeOperationSuccess(any(), any());
		verify(myConsentSvc, times(0)).completeOperationFailure(any(), any(), any());
		verifyNoMoreInteractions(myConsentSvc);
	}


	@Test
	public void testSearch_SeeResourceRejectsOuterBundle_ProvidesOperationOutcome() throws IOException {
		ourPatientProvider.store((Patient) new Patient().setActive(true).setId("PTA"));
		ourPatientProvider.store((Patient) new Patient().setActive(false).setId("PTB"));

		when(myConsentSvc.startOperation(any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.canSeeResource(any(), any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.willSeeResource(any(RequestDetails.class), any(IBaseResource.class), any())).thenAnswer(t->{
			OperationOutcome oo = new OperationOutcome();
			oo.addIssue().setDiagnostics("A DIAG");
			return new ConsentOutcome(ConsentOperationStatusEnum.REJECT, oo);
		});

		HttpGet httpGet = new HttpGet("http://localhost:" + ourPort + "/Patient");

		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(200, status.getStatusLine().getStatusCode());
			String responseContent = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseContent);
			assertThat(responseContent, containsString("A DIAG"));
		}

		verify(myConsentSvc, timeout(10000).times(1)).startOperation(any(), any());
		verify(myConsentSvc, timeout(10000).times(2)).canSeeResource(any(), any(), any());
		verify(myConsentSvc, timeout(10000).times(3)).willSeeResource(any(), any(), any());
		verify(myConsentSvc, timeout(10000).times(1)).completeOperationSuccess(any(), any());
		verify(myConsentSvc, timeout(10000).times(0)).completeOperationFailure(any(), any(), any());
		verifyNoMoreInteractions(myConsentSvc);

	}

	@Test
	public void testSearch_SeeResourceRejectsOuterBundle_ProvidesNothing() throws IOException {
		ourPatientProvider.store((Patient) new Patient().setActive(true).setId("PTA"));
		ourPatientProvider.store((Patient) new Patient().setActive(false).setId("PTB"));

		when(myConsentSvc.startOperation(any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.canSeeResource(any(RequestDetails.class), any(IBaseResource.class), any())).thenAnswer(t-> ConsentOutcome.PROCEED);
		when(myConsentSvc.willSeeResource(any(RequestDetails.class), any(IBaseResource.class), any())).thenAnswer(t-> {
			return ConsentOutcome.REJECT;
		});

		HttpGet httpGet = new HttpGet("http://localhost:" + ourPort + "/Patient");

		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(204, status.getStatusLine().getStatusCode());
			assertNull(status.getEntity());
			assertNull(status.getFirstHeader(Constants.HEADER_CONTENT_TYPE));
		}

		verify(myConsentSvc, times(1)).startOperation(any(), any());
		verify(myConsentSvc, times(2)).canSeeResource(any(), any(), any());
		verify(myConsentSvc, times(3)).willSeeResource(any(), any(), any()); // the two patients + the bundle
		verify(myConsentSvc, times(1)).completeOperationSuccess(any(), any());
		verify(myConsentSvc, times(0)).completeOperationFailure(any(), any(), any());
		verifyNoMoreInteractions(myConsentSvc);
	}

	@Test
	public void testSearch_SeeResourceRejectsInnerResource_ProvidesOperationOutcome() throws IOException {
		ourPatientProvider.store((Patient) new Patient().setActive(true).setId("PTA"));
		ourPatientProvider.store((Patient) new Patient().setActive(false).setId("PTB"));

		reset(myConsentSvc);
		when(myConsentSvc.startOperation(any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.canSeeResource(any(), any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.willSeeResource(any(RequestDetails.class), any(IBaseResource.class), any())).thenAnswer(t->{
			IBaseResource resource = (IBaseResource) t.getArguments()[1];
			if ("PTA".equals(resource.getIdElement().getIdPart())) {
				OperationOutcome oo = new OperationOutcome();
				oo.addIssue().setDiagnostics("A DIAG");
				return new ConsentOutcome(ConsentOperationStatusEnum.REJECT, oo);
			}
			return ConsentOutcome.PROCEED;
		});

		HttpGet httpGet = new HttpGet("http://localhost:" + ourPort + "/Patient");

		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(200, status.getStatusLine().getStatusCode());
			String responseContent = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseContent);
			Bundle response = ourCtx.newJsonParser().parseResource(Bundle.class, responseContent);
			assertEquals(OperationOutcome.class, response.getEntry().get(0).getResource().getClass());
			assertEquals("A DIAG", ((OperationOutcome)response.getEntry().get(0).getResource()).getIssue().get(0).getDiagnostics());
			assertEquals(Patient.class, response.getEntry().get(1).getResource().getClass());
			assertEquals("PTB", response.getEntry().get(1).getResource().getIdElement().getIdPart());
		}

		verify(myConsentSvc, timeout(1000).times(1)).startOperation(any(), any());
		verify(myConsentSvc, timeout(1000).times(2)).canSeeResource(any(), any(), any());
		verify(myConsentSvc, timeout(1000).times(3)).willSeeResource(any(), any(), any());
		verify(myConsentSvc, timeout(1000).times(1)).completeOperationSuccess(any(), any());
		verify(myConsentSvc, times(0)).completeOperationFailure(any(), any(), any());
		verifyNoMoreInteractions(myConsentSvc);
	}

	@Test
	public void testSearch_SeeResourceReplacesInnerResource() throws IOException {
		ourPatientProvider.store((Patient) new Patient().setActive(true).setId("PTA"));
		ourPatientProvider.store((Patient) new Patient().setActive(false).setId("PTB"));

		when(myConsentSvc.startOperation(any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.canSeeResource(any(), any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.willSeeResource(any(RequestDetails.class), any(IBaseResource.class), any())).thenAnswer(t->{
			IBaseResource resource = (IBaseResource) t.getArguments()[1];
			if (resource.getIdElement().getIdPart().equals("PTA")) {
				Patient replacement = new Patient();
				replacement.setId("PTA");
				replacement.addIdentifier().setSystem("REPLACEMENT");
				return new ConsentOutcome(ConsentOperationStatusEnum.PROCEED, replacement);
			}
			return ConsentOutcome.PROCEED;
		});

		HttpGet httpGet = new HttpGet("http://localhost:" + ourPort + "/Patient");

		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(200, status.getStatusLine().getStatusCode());
			String responseContent = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseContent);
			Bundle response = ourCtx.newJsonParser().parseResource(Bundle.class, responseContent);
			assertEquals(Patient.class, response.getEntry().get(0).getResource().getClass());
			assertEquals("PTA", response.getEntry().get(0).getResource().getIdElement().getIdPart());
			assertEquals("REPLACEMENT", ((Patient)response.getEntry().get(0).getResource()).getIdentifierFirstRep().getSystem());
			assertEquals(Patient.class, response.getEntry().get(1).getResource().getClass());
			assertEquals("PTB", response.getEntry().get(1).getResource().getIdElement().getIdPart());
		}

		verify(myConsentSvc, times(1)).startOperation(any(), any());
		verify(myConsentSvc, times(2)).canSeeResource(any(), any(), any());
		verify(myConsentSvc, times(4)).willSeeResource(any(), any(), any());
		verify(myConsentSvc, times(1)).completeOperationSuccess(any(), any());
		verify(myConsentSvc, times(0)).completeOperationFailure(any(), any(), any());
		verifyNoMoreInteractions(myConsentSvc);
	}

	@Test
	public void testSearch_SeeResourceModifiesInnerResource() throws IOException {
		ourPatientProvider.store((Patient) new Patient().setActive(true).setId("PTA"));
		ourPatientProvider.store((Patient) new Patient().setActive(false).setId("PTB"));

		when(myConsentSvc.startOperation(any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.canSeeResource(any(), any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.willSeeResource(any(RequestDetails.class), any(IBaseResource.class), any())).thenAnswer(t->{
			IBaseResource resource = (IBaseResource) t.getArguments()[1];
			if (resource.getIdElement().getIdPart().equals("PTA")) {
				((Patient)resource).addIdentifier().setSystem("REPLACEMENT");
			}
			return ConsentOutcome.PROCEED;
		});

		HttpGet httpGet = new HttpGet("http://localhost:" + ourPort + "/Patient");

		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(200, status.getStatusLine().getStatusCode());
			String responseContent = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseContent);
			Bundle response = ourCtx.newJsonParser().parseResource(Bundle.class, responseContent);
			assertEquals(Patient.class, response.getEntry().get(0).getResource().getClass());
			assertEquals("PTA", response.getEntry().get(0).getResource().getIdElement().getIdPart());
			assertEquals("REPLACEMENT", ((Patient)response.getEntry().get(0).getResource()).getIdentifierFirstRep().getSystem());
			assertEquals(Patient.class, response.getEntry().get(1).getResource().getClass());
			assertEquals("PTB", response.getEntry().get(1).getResource().getIdElement().getIdPart());
		}

		verify(myConsentSvc, times(1)).startOperation(any(), any());
		verify(myConsentSvc, times(2)).canSeeResource(any(), any(), any());
		verify(myConsentSvc, times(3)).willSeeResource(any(), any(), any());
		verify(myConsentSvc, times(1)).completeOperationSuccess(any(), any());
		verify(myConsentSvc, times(0)).completeOperationFailure(any(), any(), any());
		verifyNoMoreInteractions(myConsentSvc);
	}

	@Test
	public void testPage_SeeResourceReplacesInnerResource() throws IOException {
		ourPatientProvider.store((Patient) new Patient().setActive(true).setId("PTA"));
		ourPatientProvider.store((Patient) new Patient().setActive(false).setId("PTB"));

		when(myConsentSvc.startOperation(any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.canSeeResource(any(), any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.willSeeResource(any(RequestDetails.class), any(IBaseResource.class), any())).thenReturn(ConsentOutcome.PROCEED);

			String nextPageLink;
		HttpGet httpGet = new HttpGet("http://localhost:" + ourPort + "/Patient?_count=1");
		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(200, status.getStatusLine().getStatusCode());
			String responseContent = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseContent);
			Bundle bundle = ourCtx.newJsonParser().parseResource(Bundle.class, responseContent);
			nextPageLink = bundle.getLink(Constants.LINK_NEXT).getUrl();
		}

		// Now perform a page request
		reset(myConsentSvc);
		when(myConsentSvc.startOperation(any(), any())).thenReturn(ConsentOutcome.PROCEED);
		when(myConsentSvc.willSeeResource(any(RequestDetails.class), any(IBaseResource.class), any())).thenAnswer(t->{
			IBaseResource resource = (IBaseResource) t.getArguments()[1];
			if (resource.getIdElement().getIdPart().equals("PTB")) {
				Patient replacement = new Patient();
				replacement.setId("PTB");
				replacement.addIdentifier().setSystem("REPLACEMENT");
				return new ConsentOutcome(ConsentOperationStatusEnum.PROCEED, replacement);
			}
			return ConsentOutcome.PROCEED;
		});

		httpGet = new HttpGet(nextPageLink);
		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(200, status.getStatusLine().getStatusCode());
			String responseContent = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseContent);
			Bundle response = ourCtx.newJsonParser().parseResource(Bundle.class, responseContent);
			assertEquals(Patient.class, response.getEntry().get(0).getResource().getClass());
			assertEquals("PTB", response.getEntry().get(0).getResource().getIdElement().getIdPart());
			assertEquals("REPLACEMENT", ((Patient)response.getEntry().get(0).getResource()).getIdentifierFirstRep().getSystem());
		}

		verify(myConsentSvc, times(1)).startOperation(any(), any());
	}


	@Test
	public void testOutcomeException() throws IOException {
		when(myConsentSvc.startOperation(any(), any())).thenReturn(ConsentOutcome.PROCEED);

		HttpGet httpGet = new HttpGet("http://localhost:" + ourPort + "/Patient?searchThrowNullPointerException=1");

		try (CloseableHttpResponse status = ourClient.execute(httpGet)) {
			assertEquals(500, status.getStatusLine().getStatusCode());
			String responseContent = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseContent);
		}

		verify(myConsentSvc, times(0)).completeOperationSuccess(any(), any());
		verify(myConsentSvc, times(1)).completeOperationFailure(any(), myExceptionCaptor.capture(), any());

		assertEquals(Msg.code(389) + "Failed to call access method: java.lang.NullPointerException: A MESSAGE", myExceptionCaptor.getValue().getMessage());
	}

	public static class DummyPatientResourceProvider extends HashMapResourceProvider<Patient> {

		public DummyPatientResourceProvider(FhirContext theFhirContext) {
			super(theFhirContext, Patient.class);
		}

		/*
		 * searchThrowNullPointerException
		 */
		@Search
		public List<IBaseResource> searchWithWildcardRetVal(@RequiredParam(name = "searchThrowNullPointerException") StringParam theValue) {
			throw new NullPointerException("A MESSAGE");
		}

	}

	@AfterAll
	public static void afterClass() throws Exception {
		JettyUtil.closeServer(ourServer);
		ourClient.close();
	}

	@BeforeAll
	public static void beforeClass() throws Exception {
		ourServer = new Server(0);

		ourPatientProvider = new DummyPatientResourceProvider(ourCtx);
		ourSystemProvider = new DummySystemProvider();

		ServletHandler servletHandler = new ServletHandler();
		ourServlet = new RestfulServer(ourCtx);
		ourServlet.setDefaultPrettyPrint(true);
		ourServlet.setResourceProviders(ourPatientProvider);
		ourServlet.registerProvider(ourSystemProvider);
		ourServlet.setBundleInclusionRule(BundleInclusionRule.BASED_ON_RESOURCE_PRESENCE);
		ServletHolder servletHolder = new ServletHolder(ourServlet);
		servletHandler.addServletWithMapping(servletHolder, "/*");

		ourServer.setHandler(servletHandler);
		ourServer.start();
		ourPort = JettyUtil.getPortForStartedServer(ourServer);

		PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(5000, TimeUnit.MILLISECONDS);
		HttpClientBuilder builder = HttpClientBuilder.create();
		builder.setConnectionManager(connectionManager);
		ourClient = builder.build();

		ourFhirClient = ourCtx.newRestfulGenericClient("http://localhost:" + ourPort);
	}

	private static class DummySystemProvider{

		@Operation(name = "$meta", idempotent = true, returnParameters = {
			@OperationParam(name = "return", typeName = "Meta")
		})
		public IBaseParameters meta(ServletRequestDetails theRequestDetails) {
			Parameters retval = new Parameters();
			retval.addParameter("Meta", "Yes");
			return retval;
		}
	}
}
