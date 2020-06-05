/*******************************************************************************
 * SORMAS® - Surveillance Outbreak Response Management & Analysis System
 * Copyright © 2016-2018 Helmholtz-Zentrum für Infektionsforschung GmbH (HZI)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *******************************************************************************/
package de.symeda.sormas.backend.sample;

import java.math.BigInteger;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.ejb.EJB;
import javax.ejb.LocalBean;
import javax.ejb.Stateless;
import javax.persistence.NoResultException;
import javax.persistence.Query;
import javax.persistence.criteria.*;

import de.symeda.sormas.backend.common.AbstractAdoService;
import de.symeda.sormas.backend.common.AbstractCoreAdoService;
import de.symeda.sormas.backend.common.AbstractDomainObject;
import de.symeda.sormas.backend.common.QueryContext;
import de.symeda.sormas.backend.contact.Contact;
import de.symeda.sormas.backend.region.District;
import de.symeda.sormas.backend.region.Region;
import org.apache.commons.collections.CollectionUtils;

import de.symeda.sormas.api.EntityRelevanceStatus;
import de.symeda.sormas.api.sample.PathogenTestResultType;
import de.symeda.sormas.api.sample.SampleAssociationType;
import de.symeda.sormas.api.sample.SampleCriteria;
import de.symeda.sormas.api.sample.SpecimenCondition;
import de.symeda.sormas.api.user.UserRole;
import de.symeda.sormas.api.utils.DataHelper;
import de.symeda.sormas.backend.caze.Case;
import de.symeda.sormas.backend.caze.CaseService;
import de.symeda.sormas.backend.contact.ContactService;
import de.symeda.sormas.backend.facility.Facility;
import de.symeda.sormas.backend.person.Person;
import de.symeda.sormas.backend.user.User;
import de.symeda.sormas.backend.util.QueryHelper;

@Stateless
@LocalBean
public class SampleService extends AbstractCoreAdoService<Sample> {

	@EJB
	private CaseService caseService;
	@EJB
	private ContactService contactService;
	@EJB
	private PathogenTestService pathogenTestService;
	@EJB
	private AdditionalTestService additionalTestService;

	public SampleService() {
		super(Sample.class);
	}

	public List<Sample> findBy(SampleCriteria criteria, User user, String sortProperty, boolean ascending) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Sample> cq = cb.createQuery(getElementClass());
		Root<Sample> from = cq.from(getElementClass());

		final QueryContext qc = new QueryContext(cb, cq, from);
		buildJoins(qc, criteria);
		Predicate filter = buildCriteriaFilter(criteria, qc);

		if (user != null) {
			filter = and(cb, filter, createUserFilter(cb, cq, from));
		}
		if (filter != null) {
			cq.where(filter);
		}

		if (ascending) {
			cq.orderBy(cb.asc(from.get(sortProperty)));
		} else {
			cq.orderBy(cb.desc(from.get(sortProperty)));
		}

		List<Sample> resultList = em.createQuery(cq).getResultList();
		return resultList;
	}

	public List<Sample> findBy(SampleCriteria criteria, User user) {
		return findBy(criteria, user, Sample.CREATION_DATE, true);
	}

	public List<Sample> getAllActiveSamplesAfter(Date date, User user) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Sample> cq = cb.createQuery(getElementClass());
		Root<Sample> from = cq.from(getElementClass());

		Predicate filter = createActiveSamplesFilter(cb, from);

		if (user != null) {
			Predicate userFilter = createUserFilter(cb, cq, from);
			filter = AbstractAdoService.and(cb, filter, userFilter);
		}

		if (date != null) {
			Predicate dateFilter = createChangeDateFilter(cb, from, date);
			filter = AbstractAdoService.and(cb, filter, dateFilter);
		}

		cq.where(filter);
		cq.orderBy(cb.desc(from.get(Sample.CHANGE_DATE)));
		cq.distinct(true);

		return em.createQuery(cq).getResultList();
	}

	public List<String> getAllActiveUuids(User user) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<String> cq = cb.createQuery(String.class);
		Root<Sample> from = cq.from(getElementClass());

		Predicate filter = createActiveSamplesFilter(cb, from);

		if (user != null) {
			Predicate userFilter = createUserFilter(cb, cq, from);
			filter = AbstractAdoService.and(cb, filter, userFilter);
		}

		cq.where(filter);
		cq.select(from.get(Sample.UUID));

		return em.createQuery(cq).getResultList();
	}

	public int getSampleCountByCase(Case caze) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Long> cq = cb.createQuery(Long.class);
		Root<Sample> from = cq.from(getElementClass());

		cq.select(cb.count(from));
		cq.where(cb.and(
				createDefaultFilter(cb, from),
				cb.equal(from.get(Sample.ASSOCIATED_CASE), caze)));

		return em.createQuery(cq).getSingleResult().intValue();
	}

	/**
	 * Returns the sample that refers to the sample identified by the sampleUuid.
	 *
	 * @param sampleUuid The UUID of the sample to get the referral for.
	 * @return The sample that refers to this sample, or null if none is found.
	 */
	public Sample getReferredFrom(String sampleUuid) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Sample> cq = cb.createQuery(getElementClass());
		Root<Sample> from = cq.from(getElementClass());

		cq.where(cb.equal(from.get(Sample.REFERRED_TO), getByUuid(sampleUuid)));
		try {
			return em.createQuery(cq).getSingleResult();
		} catch (NoResultException e) {
			return null;
		}
	}

	public List<String> getDeletedUuidsSince(User user, Date since) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<String> cq = cb.createQuery(String.class);
		Root<Sample> sample = cq.from(Sample.class);

		Predicate filter = createUserFilter(cb, cq, sample);
		if (since != null) {
			Predicate dateFilter = cb.greaterThanOrEqualTo(sample.get(Sample.CHANGE_DATE), since);
			if (filter != null) {
				filter = cb.and(filter, dateFilter);
			} else {
				filter = dateFilter;
			}
		}

		Predicate deletedFilter = cb.equal(sample.get(Sample.DELETED), true);
		if (filter != null) {
			filter = cb.and(filter, deletedFilter);
		} else {
			filter = deletedFilter;
		}

		cq.where(filter);
		cq.select(sample.get(Sample.UUID));

		return em.createQuery(cq).getResultList();
	}

	public List<Sample> getByCaseUuids(List<String> caseUuids) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Sample> cq = cb.createQuery(Sample.class);
		Root<Sample> sampleRoot = cq.from(Sample.class);
		Join<Sample, Case> caseJoin = sampleRoot.join(Sample.ASSOCIATED_CASE, JoinType.LEFT);

		Predicate filter = cb.and(
				createDefaultFilter(cb, sampleRoot),
				caseJoin.get(AbstractDomainObject.UUID).in(caseUuids)
		);

		cq.where(filter);
		return em.createQuery(cq).getResultList();
	}

	public Map<PathogenTestResultType, Long> getNewTestResultCountByResultType(List<Long> caseIds) {

		if (CollectionUtils.isEmpty(caseIds)) {
			// Avoid empty IN clause
			return new HashMap<>();
		}

		// Avoid parameter limit by joining caseIds to a String instead of n parameters 
		StringBuilder queryBuilder = new StringBuilder();
		queryBuilder.append("WITH sortedsamples AS (SELECT DISTINCT ON (").append(Sample.ASSOCIATED_CASE).append("_id) ")
				.append(Sample.ASSOCIATED_CASE).append("_id, ").append(Sample.PATHOGEN_TEST_RESULT).append(", ").append(Sample.SAMPLE_DATE_TIME)
				.append(" FROM ").append(Sample.TABLE_NAME).append(" WHERE (").append(Sample.SPECIMEN_CONDITION).append(" IS NULL OR ")
				.append(Sample.SPECIMEN_CONDITION).append(" = '").append(SpecimenCondition.ADEQUATE.name()).append("') AND ").append(Sample.TABLE_NAME)
				.append(".").append(Sample.DELETED).append(" = false ORDER BY ").append(Sample.ASSOCIATED_CASE).append("_id, ")
				.append(Sample.SAMPLE_DATE_TIME).append(" desc) SELECT sortedsamples.").append(Sample.PATHOGEN_TEST_RESULT).append(", COUNT(")
				.append(Sample.ASSOCIATED_CASE).append("_id) FROM sortedsamples JOIN ").append(Case.TABLE_NAME).append(" ON sortedsamples.")
				.append(Sample.ASSOCIATED_CASE).append("_id = ").append(Case.TABLE_NAME).append(".id ")
				.append(" WHERE sortedsamples.").append(Sample.ASSOCIATED_CASE).append("_id IN (").append(QueryHelper.concatLongs(caseIds)).append(") ")
				.append(" GROUP BY sortedsamples." + Sample.PATHOGEN_TEST_RESULT);

		Query query = em.createNativeQuery(queryBuilder.toString());

		@SuppressWarnings("unchecked")
		List<Object[]> results = query.getResultList();

		return results.stream().filter(e -> e[0] != null).collect(Collectors.toMap(e -> PathogenTestResultType.valueOf((String) e[0]),
				e -> ((BigInteger) e[1]).longValue()));
	}

	@Override
	@SuppressWarnings({ "rawtypes" })
	@Deprecated
	public Predicate createUserFilter(CriteriaBuilder cb, CriteriaQuery cq, From<Sample, Sample> samplePath) {
		Predicate filter = createUserFilterWithoutCase(new QueryContext(cb, cq, samplePath));

		// whoever created the case the sample is associated with or is assigned to it
		// is allowed to access it
		Join<Case,Case> casePath = samplePath.join(Sample.ASSOCIATED_CASE, JoinType.LEFT);

		Predicate caseFilter = caseService.createUserFilter(cb, cq, casePath);
		filter = or(cb, filter, caseFilter);

		return filter;
	}

	@Override
	@SuppressWarnings({ "rawtypes" })
	public Predicate createUserFilter(QueryContext qc) {
		Predicate filter = createUserFilterWithoutCase(qc);

		final Path<Case> caseJoin = qc.getJoin(Sample.class, Case.class);
		final Path<Contact> contactJoin = qc.getJoin(Sample.class, Contact.class);

		final CriteriaBuilder cb = qc.getCriteriaBuilder();
		Predicate caseFilter = caseService.createUserFilter(cb, qc.getQuery(), (From<Case, Case>) caseJoin);
		Predicate contactFilter = contactService.createUserFilter(cb, qc.getQuery(), (From<Contact, Contact>) contactJoin);
		filter = or(cb, filter, caseFilter, contactFilter);

		return filter;
	}

	@SuppressWarnings("rawtypes")
	public Predicate createUserFilterWithoutCase(QueryContext qe) {
		final From<?, ?> sampleRoot = qe.getRoot();
		final Join<Sample, Case> caze = qe.getJoin(Sample.class, Case.class);
		final Join<Sample, Contact> contact = qe.getJoin(Sample.class, Contact.class);
		final CriteriaBuilder cb = qe.getCriteriaBuilder();

		Predicate filter = null;
		// user that reported it is not able to access it. Otherwise they would also need to access the case
		//filter = cb.equal(samplePath.get(Sample.REPORTING_USER), user);

		// lab users can see samples assigned to their laboratory
		User currentUser = getCurrentUser();
		if (currentUser.hasAnyUserRole(UserRole.LAB_USER, UserRole.EXTERNAL_LAB_USER)) {
			if(currentUser.getLaboratory() != null) {
				filter = or(cb, filter, cb.equal(sampleRoot.get(Sample.LAB), currentUser.getLaboratory()));			}
		}

		// only show samples of a specific disease if a limited disease is set
		if (filter != null && currentUser.getLimitedDisease() != null) {
			filter = and(cb, filter,
					cb.equal(cb.selectCase().when(cb.isNotNull(caze), caze.get(Case.DISEASE)).otherwise(contact.get(Contact.DISEASE)),
							currentUser.getLimitedDisease()));
		}

		return filter;
	}

	public QueryContext buildJoins(QueryContext qc, SampleCriteria criteria) {

		final From<?, ?> sample = qc.getRoot();

		qc.addJoin(() -> sample.join(Sample.REFERRED_TO, JoinType.LEFT));

		qc.addJoin(() -> sample.join(Sample.LAB, JoinType.LEFT));

		final Join<Sample, Case> caze = qc.addJoin(() -> sample.join(Sample.ASSOCIATED_CASE, JoinType.LEFT));
		qc.addJoin(() -> caze.join(Case.PERSON, JoinType.LEFT));
		qc.addJoin(() -> caze.join(Case.REGION, JoinType.LEFT));
		qc.addJoin(() -> caze.join(Case.DISTRICT, JoinType.LEFT));

		final Join<Sample, Contact> contact = qc.addJoin(() -> sample.join(Sample.ASSOCIATED_CONTACT, JoinType.LEFT));
		qc.addJoin(() -> contact.join(Contact.PERSON, JoinType.LEFT));
		qc.addJoin(() -> contact.join(Contact.REGION, JoinType.LEFT));
		qc.addJoin(() -> contact.join(Contact.DISTRICT, JoinType.LEFT));

		final Join<Contact, Case> contactCase = qc.addJoin(() -> contact.join(Contact.CAZE, JoinType.LEFT));
		qc.addJoin(() -> contactCase.join(Case.REGION, JoinType.LEFT), "contactCaseRegion");
		qc.addJoin(() -> contactCase.join(Case.DISTRICT, JoinType.LEFT), "contactCaseDistrict");

		return qc;
	}

	public Predicate buildCriteriaFilter(SampleCriteria criteria, QueryContext qc) {
		final Join<Sample, Case> caze = qc.getJoin(Sample.class, Case.class);
		final Join<Sample, Contact> contact = qc.getJoin(Sample.class, Contact.class);
		final From<?, ?> sample = qc.getRoot();
		final CriteriaBuilder cb = qc.getCriteriaBuilder();

		Predicate filter = null;
		final SampleAssociationType sampleAssociationType = criteria.getSampleAssociationType();
		if (sampleAssociationType == SampleAssociationType.CASE) {
			filter = and(cb, filter, cb.isNotNull(caze));
		} else if (sampleAssociationType == SampleAssociationType.CONTACT) {
			filter = and(cb, filter, cb.isNotNull(contact));
		}

		if (criteria.getRegion() != null) {
			final Join<Case, Region> caseRegion = qc.getJoin(Case.class, Region.class);
			final Join<Contact, Region> contactRegion = qc.getJoin(Contact.class, Region.class);
			final Join<Case, Region> contactCaseRegion = qc.getJoin(Case.class, Region.class, SampleFacadeEjb.CONTACT_CASE_REGION);
			qc.addExpression(SampleFacadeEjb.REGION, cb.selectCase().when(cb.isNotNull(caseRegion),
					caseRegion.get(Region.UUID)).otherwise(cb.selectCase().when(cb.isNotNull(contactRegion),
					contactRegion.get(Region.UUID)).otherwise(contactCaseRegion.get(Region.UUID))));
			filter = and(cb, filter, cb.equal(qc.getExpression(SampleFacadeEjb.REGION), criteria.getRegion().getUuid()));
		}
		if (criteria.getDistrict() != null) {
			final Join<Case, District> caseDistrict = qc.getJoin(Case.class, District.class);
			final Join<Contact, District> contactDistrict = qc.getJoin(Contact.class, District.class);
			final Join<Case, District> contactCaseDistrict = qc.getJoin(Case.class, District.class, SampleFacadeEjb.CONTACT_CASE_DISTRICT);
			qc.addExpression(SampleFacadeEjb.DISTRICT, cb.selectCase().when(cb.isNotNull(caseDistrict),
					caseDistrict.get(District.UUID)).otherwise(cb.selectCase().when(cb.isNotNull(contactDistrict),
					contactDistrict.get(District.UUID)).otherwise(contactCaseDistrict.get(District.UUID))));
			filter = and(cb, filter, cb.equal(qc.getExpression(SampleFacadeEjb.DISTRICT), criteria.getDistrict().getUuid()));
		}
		if (criteria.getLaboratory() != null) {
			filter = and(cb, filter, cb.equal(qc.getJoin(Sample.class, Facility.class).get(Facility.UUID), criteria.getLaboratory().getUuid()));
		}
		if (criteria.getShipped() != null) {
			filter = and(cb, filter, cb.equal(sample.get(Sample.SHIPPED), criteria.getShipped()));
		}
		if (criteria.getReceived() != null) {
			filter = and(cb, filter, cb.equal(sample.get(Sample.RECEIVED), criteria.getReceived()));
		}
		if (criteria.getReferred() != null) {
			if (criteria.getReferred().equals(Boolean.TRUE)) {
				filter = and(cb, filter, cb.isNotNull(sample.get(Sample.REFERRED_TO)));
			} else {
				filter = and(cb, filter, cb.isNull(sample.get(Sample.REFERRED_TO)));
			}
		}
		if (criteria.getPathogenTestResult() != null) {
			filter = and(cb, filter, cb.equal(sample.get(Sample.PATHOGEN_TEST_RESULT), criteria.getPathogenTestResult()));
		}
		if (criteria.getCaseClassification() != null) {
			filter = and(cb, filter, cb.equal(caze.get(Case.CASE_CLASSIFICATION), criteria.getCaseClassification()));
		}
		if (criteria.getDisease() != null) {
			qc.addExpression(SampleFacadeEjb.DISEASE,
					cb.selectCase().when(cb.isNotNull(caze), caze.get(Case.DISEASE)).otherwise(contact.get(Contact.DISEASE)));
			filter = and(cb, filter, cb.equal(qc.getExpression(SampleFacadeEjb.DISEASE), criteria.getDisease()));
		}
		if (criteria.getCaze() != null) {
			filter = and(cb, filter, cb.equal(caze.get(Case.UUID), criteria.getCaze().getUuid()));
		}
		if (criteria.getContact() != null) {
			filter = and(cb, filter, cb.equal(contact.get(Contact.UUID), criteria.getContact().getUuid()));
		}
		if (criteria.getSampleReportDateFrom() != null && criteria.getSampleReportDateTo() != null) {
			filter = and(cb, filter, cb.between(sample.get(Sample.SAMPLE_DATE_TIME), criteria.getSampleReportDateFrom(),
					criteria.getSampleReportDateTo()));
		} else if (criteria.getSampleReportDateFrom() != null) {
			filter = and(cb, filter,
					cb.greaterThanOrEqualTo(sample.get(Sample.SAMPLE_DATE_TIME), criteria.getSampleReportDateFrom()));
		} else if (criteria.getSampleReportDateTo() != null) {
			filter = and(cb, filter,
					cb.lessThanOrEqualTo(sample.get(Sample.SAMPLE_DATE_TIME), criteria.getSampleReportDateTo()));
		}
		if (criteria.getSpecimenCondition() != null) {
			filter = and(cb, filter, cb.equal(sample.get(Sample.SPECIMEN_CONDITION), criteria.getSpecimenCondition()));
		}
		if (criteria.getRelevanceStatus() != null) {
			if (criteria.getRelevanceStatus() == EntityRelevanceStatus.ACTIVE) {
				filter = and(cb, filter, cb.or(
						cb.equal(caze.get(Case.ARCHIVED), false),
						cb.isNull(caze.get(Case.ARCHIVED))));
			} else if (criteria.getRelevanceStatus() == EntityRelevanceStatus.ARCHIVED) {
				filter = and(cb, filter,cb.equal(caze.get(Case.ARCHIVED), true));
			}
		}
		if (criteria.getDeleted() != null) {
			filter = and(cb, filter, cb.equal(sample.get(Sample.DELETED), criteria.getDeleted()));
		}

		if (criteria.getCaseCodeIdLike() != null) {
			final Join<Case, Person> casePerson = qc.getJoin(Case.class, Person.class);
			final Join<Sample, Facility> lab = qc.getJoin(Sample.class, Facility.class);
			String[] textFilters = criteria.getCaseCodeIdLike().split("\\s+");
			for (int i=0; i<textFilters.length; i++)
			{
				String textFilter = "%" + textFilters[i].toLowerCase() + "%";
				if (!DataHelper.isNullOrEmpty(textFilter)) {
					Predicate likeFilters = cb.or(
							cb.like(cb.lower(caze.get(Case.UUID)), textFilter),
							cb.like(cb.lower(casePerson.get(Person.FIRST_NAME)), textFilter),
							cb.like(cb.lower(casePerson.get(Person.LAST_NAME)), textFilter),
							cb.like(cb.lower(caze.get(Case.EPID_NUMBER)), textFilter),
							cb.like(cb.lower(sample.get(Sample.LAB_SAMPLE_ID)), textFilter),
							cb.like(cb.lower(sample.get(Sample.FIELD_SAMPLE_ID)), textFilter),
							cb.like(cb.lower(lab.get(Facility.NAME)), textFilter));
					filter = and(cb, filter, likeFilters);
				}
			}
		}

		return filter;
	}

	@Override
	public void delete(Sample sample) {
		// Mark all pathogen tests of this sample as deleted
		for (PathogenTest pathogenTest : sample.getPathogenTests()) {
			pathogenTestService.delete(pathogenTest);
		}

		// Delete all additional tests of this sample
		for (AdditionalTest additionalTest : sample.getAdditionalTests()) {
			additionalTestService.delete(additionalTest);
		}

		// Remove the reference from another sample to this sample if existing
		Sample referralSample = getReferredFrom(sample.getUuid());
		if (referralSample != null) {
			referralSample.setReferredTo(null);
			ensurePersisted(referralSample);
		}

		super.delete(sample);
	}

	/**
	 * Creates a filter that excludes all samples that are either {@link CoreAdo#deleted} or associated with
	 * cases that are {@link Case#archived}.
	 */
	public Predicate createActiveSamplesFilter(CriteriaBuilder cb, Root<Sample> root) {
		Join<Sample, Case> caze = root.join(Sample.ASSOCIATED_CASE, JoinType.LEFT);
		return cb.and(
				cb.isFalse(caze.get(Case.ARCHIVED)),
				cb.isFalse(root.get(Case.DELETED)));
	}

	/**
	 * Creates a default filter that should be used as the basis of queries that do not use {@link SampleCriteria}.
	 * This essentially removes {@link CoreAdo#deleted} samples from the queries.
	 */
	public Predicate createDefaultFilter(CriteriaBuilder cb, Root<Sample> root) {
		return cb.isFalse(root.get(Sample.DELETED));
	}

}
