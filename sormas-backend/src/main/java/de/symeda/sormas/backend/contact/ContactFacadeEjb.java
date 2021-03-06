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
package de.symeda.sormas.backend.contact;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.security.RolesAllowed;
import javax.ejb.EJB;
import javax.ejb.LocalBean;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Order;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.validation.constraints.NotNull;

import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.symeda.sormas.api.Disease;
import de.symeda.sormas.api.Language;
import de.symeda.sormas.api.caze.CaseReferenceDto;
import de.symeda.sormas.api.caze.MapCaseDto;
import de.symeda.sormas.api.contact.ContactClassification;
import de.symeda.sormas.api.contact.ContactCriteria;
import de.symeda.sormas.api.contact.ContactDto;
import de.symeda.sormas.api.contact.ContactExportDto;
import de.symeda.sormas.api.contact.ContactFacade;
import de.symeda.sormas.api.contact.ContactFollowUpDto;
import de.symeda.sormas.api.contact.ContactIndexDetailedDto;
import de.symeda.sormas.api.contact.ContactIndexDto;
import de.symeda.sormas.api.contact.ContactLogic;
import de.symeda.sormas.api.contact.ContactReferenceDto;
import de.symeda.sormas.api.contact.ContactSimilarityCriteria;
import de.symeda.sormas.api.contact.ContactStatus;
import de.symeda.sormas.api.contact.DashboardContactDto;
import de.symeda.sormas.api.contact.FollowUpStatus;
import de.symeda.sormas.api.contact.MapContactDto;
import de.symeda.sormas.api.contact.SimilarContactDto;
import de.symeda.sormas.api.i18n.I18nProperties;
import de.symeda.sormas.api.i18n.Validations;
import de.symeda.sormas.api.person.PersonReferenceDto;
import de.symeda.sormas.api.region.DistrictReferenceDto;
import de.symeda.sormas.api.region.RegionReferenceDto;
import de.symeda.sormas.api.task.TaskContext;
import de.symeda.sormas.api.task.TaskCriteria;
import de.symeda.sormas.api.task.TaskPriority;
import de.symeda.sormas.api.task.TaskStatus;
import de.symeda.sormas.api.task.TaskType;
import de.symeda.sormas.api.user.UserRight;
import de.symeda.sormas.api.user.UserRole;
import de.symeda.sormas.api.utils.DateHelper;
import de.symeda.sormas.api.utils.SortProperty;
import de.symeda.sormas.api.utils.ValidationRuntimeException;
import de.symeda.sormas.api.utils.YesNoUnknown;
import de.symeda.sormas.api.visit.VisitResult;
import de.symeda.sormas.api.visit.VisitStatus;
import de.symeda.sormas.api.visit.VisitSummaryExportDetailsDto;
import de.symeda.sormas.api.visit.VisitSummaryExportDto;
import de.symeda.sormas.backend.caze.Case;
import de.symeda.sormas.backend.caze.CaseFacadeEjb;
import de.symeda.sormas.backend.caze.CaseFacadeEjb.CaseFacadeEjbLocal;
import de.symeda.sormas.backend.caze.CaseService;
import de.symeda.sormas.backend.common.AbstractAdoService;
import de.symeda.sormas.backend.common.AbstractDomainObject;
import de.symeda.sormas.backend.facility.Facility;
import de.symeda.sormas.backend.location.Location;
import de.symeda.sormas.backend.person.Person;
import de.symeda.sormas.backend.person.PersonFacadeEjb;
import de.symeda.sormas.backend.person.PersonService;
import de.symeda.sormas.backend.region.District;
import de.symeda.sormas.backend.region.DistrictFacadeEjb;
import de.symeda.sormas.backend.region.DistrictService;
import de.symeda.sormas.backend.region.Region;
import de.symeda.sormas.backend.region.RegionFacadeEjb;
import de.symeda.sormas.backend.region.RegionService;
import de.symeda.sormas.backend.symptoms.Symptoms;
import de.symeda.sormas.backend.task.Task;
import de.symeda.sormas.backend.task.TaskService;
import de.symeda.sormas.backend.user.User;
import de.symeda.sormas.backend.user.UserFacadeEjb;
import de.symeda.sormas.backend.user.UserRoleConfigFacadeEjb.UserRoleConfigFacadeEjbLocal;
import de.symeda.sormas.backend.user.UserService;
import de.symeda.sormas.backend.util.DateHelper8;
import de.symeda.sormas.backend.util.DtoHelper;
import de.symeda.sormas.backend.util.ModelConstants;
import de.symeda.sormas.backend.util.QueryHelper;
import de.symeda.sormas.backend.visit.Visit;
import de.symeda.sormas.backend.visit.VisitService;
import de.symeda.sormas.backend.visit.VisitSummaryExportDetails;

@Stateless(name = "ContactFacade")
public class ContactFacadeEjb implements ContactFacade {

	private final Logger logger = LoggerFactory.getLogger(getClass());

	@PersistenceContext(unitName = ModelConstants.PERSISTENCE_UNIT_NAME)
	private EntityManager em;

	@EJB
	private ContactService contactService;

	@EJB
	private ContactListCriteriaBuilder listCriteriaBuilder;

	@EJB
	private CaseService caseService;
	@EJB
	private PersonService personService;
	@EJB
	private UserService userService;
	@EJB
	private VisitService visitService;
	@EJB
	private TaskService taskService;
	@EJB
	private RegionService regionService;
	@EJB
	private DistrictService districtService;
	@EJB
	private CaseFacadeEjbLocal caseFacade;
	@EJB
	private UserRoleConfigFacadeEjbLocal userRoleConfigFacade;
	@EJB
	private ContactEditAuthorization contactEditAuthorization;

	@Override
	public List<String> getAllActiveUuids() {
		User user = userService.getCurrentUser();

		if (user == null) {
			return Collections.emptyList();
		}

		return contactService.getAllActiveUuids(user);
	}

	@Override
	public List<ContactDto> getAllActiveContactsAfter(Date date) {
		User user = userService.getCurrentUser();

		if (user == null) {
			return Collections.emptyList();
		}

		return contactService.getAllActiveContactsAfter(date).stream()
				.map(c -> toDto(c))
				.collect(Collectors.toList());
	}

	@Override
	public List<ContactDto> getByUuids(List<String> uuids) {
		return contactService.getByUuids(uuids)
				.stream()
				.map(c -> toDto(c))
				.collect(Collectors.toList());
	}

	@Override
	public List<String> getDeletedUuidsSince(Date since) {
		User user = userService.getCurrentUser();

		if (user == null) {
			return Collections.emptyList();
		}

		return contactService.getDeletedUuidsSince(user, since);
	}

	@Override
	public ContactDto getContactByUuid(String uuid) {
		return toDto(contactService.getByUuid(uuid));
	}

	@Override
	public Boolean isValidContactUuid(String uuid) {
		return contactService.exists(uuid);
	}

	@Override
	public ContactReferenceDto getReferenceByUuid(String uuid) {
		return toReferenceDto(contactService.getByUuid(uuid));
	}

	@Override
	public ContactDto saveContact(ContactDto dto) {
		return saveContact(dto, true);
	}

	public ContactDto saveContact(ContactDto dto, boolean handleChanges) {
		validate(dto);

		final String contactUuid = dto.getUuid();
		final ContactDto existingContact = contactUuid != null ? toDto(contactService.getByUuid(contactUuid)) : null;

		// taking this out because it may lead to server problems
		// case disease can change over time and there is currently no mechanism that would delete all related contacts
		// in this case the best solution is to only keep this hidden from the UI and still allow it in the backend
		//		if (!DiseaseHelper.hasContactFollowUp(entity.getCaze().getDisease(), entity.getCaze().getPlagueType())) {
		//			throw new UnsupportedOperationException("Contact creation is not allowed for diseases that don't have contact follow-up.");
		//		}


		Contact entity = fromDto(dto);

		contactService.ensurePersisted(entity);

		if (handleChanges) {
			updateContactVisitAssociations(existingContact, entity);

			contactService.updateFollowUpUntilAndStatus(entity);
			contactService.udpateContactStatus(entity);

			if (entity.getCaze() != null) {
				caseFacade.onCaseChanged(CaseFacadeEjbLocal.toDto(entity.getCaze()), entity.getCaze());
			}
		}

		return toDto(entity);
	}

	private void updateContactVisitAssociations(ContactDto existingContact, Contact contact) {
		if (existingContact != null && existingContact.getReportDateTime() == contact.getReportDateTime()
				&& existingContact.getLastContactDate() == contact.getLastContactDate()
				&& existingContact.getFollowUpUntil() == contact.getFollowUpUntil()
				&& existingContact.getDisease() == contact.getDisease()) {
			return;
		}

		if (existingContact != null) {
			for (Visit visit : contact.getVisits()) {
				visit.getContacts().remove(contact);
			}
		}

		Date contactStartDate = ContactLogic.getStartDate(contact.getLastContactDate(), contact.getReportDateTime());
		for (Visit visit : visitService.getAllRelevantVisits(contact.getPerson(), contact.getDisease(), contactStartDate,
				contact.getFollowUpUntil() != null ? contact.getFollowUpUntil() : contactStartDate)) {
			contact.getVisits().add(visit); // Necessary for further logic during the contact save process
			visit.getContacts().add(contact);
		}
	}

	@Override
	public List<MapContactDto> getContactsForMap(RegionReferenceDto regionRef, DistrictReferenceDto districtRef, Disease disease, Date fromDate, Date toDate, List<MapCaseDto> mapCaseDtos) {
		User user = userService.getCurrentUser();
		Region region = regionService.getByReferenceDto(regionRef);
		District district = districtService.getByReferenceDto(districtRef);
		List<String> caseUuids = new ArrayList<>();
		for (MapCaseDto mapCaseDto : mapCaseDtos) {
			caseUuids.add(mapCaseDto.getUuid());
		}

		if (user == null) {
			return Collections.emptyList();
		}

		return contactService.getContactsForMap(region, district, disease, fromDate, toDate, user, caseUuids);
	}

	@Override
	public void deleteContact(String contactUuid) {
		User user = userService.getCurrentUser();
		if (!userRoleConfigFacade.getEffectiveUserRights(user.getUserRoles().toArray(new UserRole[user.getUserRoles().size()])).contains(UserRight.CONTACT_DELETE)) {
			throw new UnsupportedOperationException("User " + user.getUuid() + " is not allowed to delete contacts.");
		}

		Contact contact = contactService.getByUuid(contactUuid);
		contactService.delete(contact);

		if (contact.getCaze() != null) {
			caseFacade.onCaseChanged(CaseFacadeEjbLocal.toDto(contact.getCaze()), contact.getCaze());
		}
	}

	@Override
	public List<ContactExportDto> getExportList(ContactCriteria contactCriteria, int first, int max, Language userLanguage) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<ContactExportDto> cq = cb.createQuery(ContactExportDto.class);
		Root<Contact> contact = cq.from(Contact.class);
		Join<Contact, Case> contactCase = contact.join(Contact.CAZE, JoinType.LEFT);
		Join<Contact, Person> contactPerson = contact.join(Contact.PERSON, JoinType.LEFT);
		Join<Contact, Location> address = contactPerson.join(Person.ADDRESS, JoinType.LEFT);
		Join<Contact, Region> addressRegion = address.join(Location.REGION, JoinType.LEFT);
		Join<Contact, District> addressDistrict = address.join(Location.DISTRICT, JoinType.LEFT);
		Join<Contact, Region> contactRegion = contact.join(Contact.REGION, JoinType.LEFT);
		Join<Contact, District> contactDistrict = contact.join(Contact.DISTRICT, JoinType.LEFT);
		Join<Person, Facility> occupationFacility = contactPerson.join(Person.OCCUPATION_FACILITY, JoinType.LEFT);

		cq.multiselect(
				contact.get(Contact.ID),
				contactPerson.get(Person.ID),
				contact.get(Contact.UUID),
				contactCase.get(Case.UUID),
				contactCase.get(Case.CASE_CLASSIFICATION),
				contact.get(Contact.DISEASE),
				contact.get(Contact.DISEASE_DETAILS),
				contact.get(Contact.CONTACT_CLASSIFICATION),
				contact.get(Contact.LAST_CONTACT_DATE),
				contactPerson.get(Person.FIRST_NAME),
				contactPerson.get(Person.LAST_NAME),
				contactPerson.get(Person.SEX),
				contactPerson.get(Person.APPROXIMATE_AGE),
				contactPerson.get(Person.APPROXIMATE_AGE_TYPE),
				contact.get(Contact.REPORT_DATE_TIME),
				contact.get(Contact.CONTACT_PROXIMITY),
				contact.get(Contact.CONTACT_STATUS),
				contact.get(Contact.FOLLOW_UP_STATUS),
				contact.get(Contact.FOLLOW_UP_UNTIL),
				contact.get(Contact.QUARANTINE),
				contact.get(Contact.QUARANTINE_FROM),
				contact.get(Contact.QUARANTINE_TO),
				contact.get(Contact.QUARANTINE_HELP_NEEDED),
				contactPerson.get(Person.PRESENT_CONDITION),
				contactPerson.get(Person.DEATH_DATE),
				addressRegion.get(Region.NAME),
				addressDistrict.get(District.NAME),
				address.get(Location.CITY),
				address.get(Location.ADDRESS),
				address.get(Location.POSTAL_CODE),
				contactPerson.get(Person.PHONE),
				contactPerson.get(Person.PHONE_OWNER),
				contactPerson.get(Person.OCCUPATION_TYPE),
				contactPerson.get(Person.OCCUPATION_DETAILS),
				occupationFacility.get(Facility.NAME),
				occupationFacility.get(Facility.UUID),
				contactPerson.get(Person.OCCUPATION_FACILITY_DETAILS),
				contactRegion.get(Region.NAME),
				contactDistrict.get(District.NAME));

        Predicate filter = listCriteriaBuilder.buildContactFilter(contactCriteria, cb, contact, cq);

		if (filter != null) {
			cq.where(filter);
		}

		cq.orderBy(cb.desc(contact.get(Contact.REPORT_DATE_TIME)));

		List<ContactExportDto> exportContacts = em.createQuery(cq).setFirstResult(first).setMaxResults(max).getResultList();

		if (!exportContacts.isEmpty()) {
			List<Long> exportContactIds = exportContacts.stream().map(e -> e.getId()).collect(Collectors.toList());

			CriteriaQuery<VisitSummaryExportDetails> visitsCq = cb.createQuery(VisitSummaryExportDetails.class);
			Root<Contact> visitsCqRoot = visitsCq.from(Contact.class);
			Join<Contact, Visit> visitsJoin = visitsCqRoot.join(Contact.VISITS, JoinType.LEFT);
			Join<Visit, Symptoms> visitSymptomsJoin = visitsJoin.join(Visit.SYMPTOMS, JoinType.LEFT);

			visitsCq.where(ContactService.and(cb,
					contact.get(AbstractDomainObject.ID).in(exportContactIds),
					cb.isNotEmpty(visitsCqRoot.get(Contact.VISITS)))
					);
			visitsCq.multiselect(
					visitsCqRoot.get(AbstractDomainObject.ID),
					visitsJoin.get(Visit.VISIT_DATE_TIME),
					visitsJoin.get(Visit.VISIT_STATUS),
					visitSymptomsJoin
					);

			List<VisitSummaryExportDetails> visitSummaries = em.createQuery(visitsCq).getResultList();

			// Adding a second query here is not perfect, but selecting the last cooperative visit with a criteria query
			// doesn't seem to be possible and using a native query is not an option because of user filters
			for (ContactExportDto exportContact : exportContacts) {
				List<VisitSummaryExportDetails> visits = visitSummaries.stream().filter(v -> v.getContactId() == exportContact.getId()).collect(Collectors.toList());

				VisitSummaryExportDetails lastCooperativeVisit = visits.stream()
						.filter(v -> v.getVisitStatus() == VisitStatus.COOPERATIVE)
						.max((v1, v2) -> v1.getVisitDateTime().compareTo(v2.getVisitDateTime()))
						.orElse(null);

				exportContact.setNumberOfVisits(visits.size());
				if (lastCooperativeVisit != null) {
					exportContact.setLastCooperativeVisitDate(lastCooperativeVisit.getVisitDateTime());
					exportContact.setLastCooperativeVisitSymptoms(lastCooperativeVisit.getSymptoms().toHumanString(true, userLanguage));
					exportContact.setLastCooperativeVisitSymptomatic(lastCooperativeVisit.getSymptoms().getSymptomatic() ? YesNoUnknown.YES : YesNoUnknown.NO);
				}
			}
		}

		return exportContacts;
	}

    @Override
    public List<VisitSummaryExportDto> getVisitSummaryExportList(ContactCriteria contactCriteria, int first, int max, Language userLanguage) {
		final CriteriaBuilder cb = em.getCriteriaBuilder();
		final CriteriaQuery<VisitSummaryExportDto> cq = cb.createQuery(VisitSummaryExportDto.class);
		final Root<Contact> contactRoot = cq.from(Contact.class);
		final Join<Contact, Person> contactPerson = contactRoot.join(Contact.PERSON, JoinType.LEFT);

		cq.multiselect(
				contactRoot.get(Contact.UUID),
				contactRoot.get(Contact.ID),
				contactPerson.get(Person.FIRST_NAME),
				contactPerson.get(Person.LAST_NAME),
				cb.<Date>selectCase().when(cb.isNotNull(contactRoot.get(Contact.LAST_CONTACT_DATE)),
						contactRoot.get(Contact.LAST_CONTACT_DATE))
				.otherwise(contactRoot.get(Contact.REPORT_DATE_TIME)),
				contactRoot.get(Contact.FOLLOW_UP_UNTIL));

		cq.where(AbstractAdoService.and(cb,
				listCriteriaBuilder.buildContactFilter(contactCriteria, cb, contactRoot, cq),
				cb.isNotEmpty(contactRoot.get(Contact.VISITS))));
		cq.orderBy(cb.asc(contactRoot.get(Contact.REPORT_DATE_TIME)));

		List<VisitSummaryExportDto> visitSummaries = em.createQuery(cq).setFirstResult(first).setMaxResults(max).getResultList();

		if (!visitSummaries.isEmpty()) {
			List<String> visitSummaryUuids = visitSummaries.stream().map(e -> e.getUuid()).collect(Collectors.toList());

			CriteriaQuery<VisitSummaryExportDetails> visitsCq = cb.createQuery(VisitSummaryExportDetails.class);
			Root<Contact> visitsCqRoot = visitsCq.from(Contact.class);
			Join<Contact, Visit> visitsJoin = visitsCqRoot.join(Contact.VISITS, JoinType.LEFT);
			Join<Visit, Symptoms> visitSymptomsJoin = visitsJoin.join(Visit.SYMPTOMS, JoinType.LEFT);

			visitsCq.where(ContactService.and(cb,
					contactRoot.get(AbstractDomainObject.UUID).in(visitSummaryUuids),
					cb.isNotEmpty(visitsCqRoot.get(Contact.VISITS)))
					);
			visitsCq.multiselect(
					visitsCqRoot.get(AbstractDomainObject.ID),
					visitsJoin.get(Visit.VISIT_DATE_TIME),
					visitsJoin.get(Visit.VISIT_STATUS),
					visitSymptomsJoin
					);
			visitsCq.orderBy(cb.asc(visitsJoin.get(Visit.VISIT_DATE_TIME)));

			List<VisitSummaryExportDetails> visitSummaryDetails = em.createQuery(visitsCq).getResultList();

			Map<Long, VisitSummaryExportDto> visitSummaryMap = visitSummaries.stream().collect(Collectors.toMap(VisitSummaryExportDto::getContactId, Function.identity()));
			visitSummaryDetails.stream().forEach(v -> visitSummaryMap.get(v.getContactId()).getVisitDetails().add(new VisitSummaryExportDetailsDto(v.getVisitDateTime(), v.getVisitStatus(), v.getSymptoms().toHumanString(true, userLanguage))));
		}

		return visitSummaries;
	}

	@Override
	public long countMaximumFollowUpDays(ContactCriteria contactCriteria) {
		final CriteriaBuilder cb = em.getCriteriaBuilder();
		final CriteriaQuery<Long> cq = cb.createQuery(Long.class);
		final Root<Contact> contactRoot = cq.from(Contact.class);
		contactRoot.join(Contact.VISITS, JoinType.LEFT);

		Predicate filter = listCriteriaBuilder.buildContactFilter(contactCriteria, cb, contactRoot, cq);
		if (filter != null) {
			cq.where(filter);
		}

		cq.select(contactRoot.get(AbstractDomainObject.ID));
		List<Long> contactIds = em.createQuery(cq).getResultList();

		if (!contactIds.isEmpty()) {
			return contactIds.stream()
					.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
					.entrySet()
					.stream()
					.max((e1, e2) -> e1.getValue().compareTo(e2.getValue()))
					.get()
					.getValue();
		} else {
			return 0L;
		}
	}

	@Override
	public long count(ContactCriteria contactCriteria) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Long> cq = cb.createQuery(Long.class);
		Root<Contact> root = cq.from(Contact.class);

		Predicate filter = listCriteriaBuilder.buildContactFilter(contactCriteria, cb, root, cq);

		if (filter != null) {
			cq.where(filter);
		}

		cq.select(cb.count(root));
		return em.createQuery(cq).getSingleResult();
	}

	@Override
	public List<ContactFollowUpDto> getContactFollowUpList(ContactCriteria contactCriteria, Date referenceDate, int interval,
														   Integer first, Integer max,
														   List<SortProperty> sortProperties) {
		Date end = DateHelper.getEndOfDay(referenceDate);
		Date start = DateHelper.getStartOfDay(DateHelper.subtractDays(end, interval));

		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<ContactFollowUpDto> cq = cb.createQuery(ContactFollowUpDto.class);
		Root<Contact> contact = cq.from(Contact.class);
		Join<Contact, Person> contactPerson = contact.join(Contact.PERSON, JoinType.LEFT);
		Join<Contact, User> contactOfficer = contact.join(Contact.CONTACT_OFFICER, JoinType.LEFT);

		cq.multiselect(contact.get(Contact.UUID), contactPerson.get(Person.UUID),
				contactPerson.get(Person.FIRST_NAME), contactPerson.get(Person.LAST_NAME),
				contactOfficer.get(User.UUID), contactOfficer.get(User.FIRST_NAME),
				contactOfficer.get(User.LAST_NAME), contact.get(Contact.LAST_CONTACT_DATE),
				contact.get(Contact.REPORT_DATE_TIME), contact.get(Contact.FOLLOW_UP_UNTIL),
				contact.get(Contact.DISEASE));

		// Only use user filter if no restricting case is specified
		Predicate filter = listCriteriaBuilder.buildContactFilter(contactCriteria, cb, contact, cq);

		if (filter != null) {
			cq.where(filter);
		}

		if (sortProperties != null && sortProperties.size() > 0) {
			List<Order> order = new ArrayList<Order>(sortProperties.size());
			for (SortProperty sortProperty : sortProperties) {
				Expression<?> expression;
				switch (sortProperty.propertyName) {
				case ContactFollowUpDto.UUID:
				case ContactFollowUpDto.LAST_CONTACT_DATE:
				case ContactFollowUpDto.REPORT_DATE_TIME:
				case ContactFollowUpDto.FOLLOW_UP_UNTIL:
					expression = contact.get(sortProperty.propertyName);
					break;
				case ContactFollowUpDto.PERSON:
					expression = contactPerson.get(Person.FIRST_NAME);
					order.add(sortProperty.ascending ? cb.asc(expression) : cb.desc(expression));
					expression = contactPerson.get(Person.LAST_NAME);
					break;
				case ContactFollowUpDto.CONTACT_OFFICER:
					expression = contactOfficer.get(User.FIRST_NAME);
					order.add(sortProperty.ascending ? cb.asc(expression) : cb.desc(expression));
					expression = contactOfficer.get(User.LAST_NAME);
					break;
				default:
					throw new IllegalArgumentException(sortProperty.propertyName);
				}
				order.add(sortProperty.ascending ? cb.asc(expression) : cb.desc(expression));
			}
			cq.orderBy(order);
		} else {
			cq.orderBy(cb.desc(contact.get(Contact.CHANGE_DATE)));
		}

		List<ContactFollowUpDto> resultList = em.createQuery(cq).setFirstResult(first).setMaxResults(max).getResultList();

		if (!resultList.isEmpty()) {

			List<String> contactUuids = resultList.stream().map(d -> d.getUuid()).collect(Collectors.toList());

			CriteriaQuery<Object[]> visitsCq = cb.createQuery(Object[].class);
			Root<Contact> visitsCqRoot = visitsCq.from(Contact.class);
			Join<Contact, Visit> visitsJoin = visitsCqRoot.join(Contact.VISITS, JoinType.LEFT);
			Join<Visit, Symptoms> visitSymptomsJoin = visitsJoin.join(Visit.SYMPTOMS, JoinType.LEFT);

			visitsCq.where(AbstractAdoService.and(cb,
					contact.get(AbstractDomainObject.UUID).in(contactUuids),
					cb.isNotEmpty(visitsCqRoot.get(Contact.VISITS)),
					cb.between(visitsJoin.get(Visit.VISIT_DATE_TIME), start, end))
					);
			visitsCq.multiselect(
					visitsCqRoot.get(Contact.UUID),
					visitsJoin.get(Visit.VISIT_DATE_TIME),
					visitsJoin.get(Visit.VISIT_STATUS),
					visitSymptomsJoin.get(Symptoms.SYMPTOMATIC)
					);

			List<Object[]> visits = em.createQuery(visitsCq).getResultList();
			Map<String, ContactFollowUpDto> resultMap = resultList.stream().collect(Collectors.toMap(ContactFollowUpDto::getUuid, Function.identity()));
			resultMap.values().stream().forEach(contactFollowUpDto -> {
				contactFollowUpDto.initVisitSize(interval + 1);
			});
			visits.stream().forEach(v -> {
				int day = DateHelper.getDaysBetween(start, (Date) v[1]);
				VisitResult result = getVisitResult((VisitStatus) v[2], (boolean) v[3]);
				resultMap.get(v[0]).getVisitResults()[day - 1] = result;
			});
		}

		return resultList;
	}

	private VisitResult getVisitResult(VisitStatus status, boolean symptomatic) {
		if (VisitStatus.UNCOOPERATIVE.equals(status)) {
			return VisitResult.UNCOOPERATIVE;
		}
		if (VisitStatus.UNAVAILABLE.equals(status)) {
			return VisitResult.UNAVAILABLE;
		}
		if (symptomatic) {
			return VisitResult.SYMPTOMATIC;
		}
		return VisitResult.NOT_SYMPTOMATIC;
	}

    @Override
    public List<ContactIndexDto> getIndexList(ContactCriteria contactCriteria, Integer first, Integer max, List<SortProperty> sortProperties) {
		CriteriaQuery<ContactIndexDto> query = listCriteriaBuilder.buildIndexCriteria(contactCriteria, sortProperties);

		if (first != null && max != null) {
            return em.createQuery(query).setFirstResult(first).setMaxResults(max).getResultList();
        } else {
            return em.createQuery(query).getResultList();
        }
    }

	@Override
	public List<ContactIndexDetailedDto> getIndexDetailedList(ContactCriteria contactCriteria, Integer first, Integer max, List<SortProperty> sortProperties) {
		CriteriaQuery<ContactIndexDetailedDto> query = listCriteriaBuilder.buildIndexDetailedCriteria(contactCriteria, sortProperties);

		if (first != null && max != null) {
			return em.createQuery(query).setFirstResult(first).setMaxResults(max).getResultList();
		} else {
			return em.createQuery(query).getResultList();
		}
	}

	@Override
	public int[] getContactCountsByCasesForDashboard(List<Long> contactIds) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Long> cq = cb.createQuery(Long.class);
		Root<Contact> contact = cq.from(Contact.class);
		Join<Contact, Case> caseJoin = contact.join(Contact.CAZE);

		cq.where(contact.get(Contact.ID).in(contactIds));
		cq.select(caseJoin.get(Case.ID));
		cq.distinct(true);

		List<Long> caseIds = em.createQuery(cq).getResultList();

		if (caseIds.isEmpty()) {
			return new int[3];

		} else {
			int[] counts = new int[3];
			CriteriaQuery<Long> cq2 = cb.createQuery(Long.class);
			Root<Contact> contact2 = cq2.from(Contact.class);
			cq2.groupBy(contact2.get(Contact.CAZE));

			cq2.where(contact2.get(Contact.CAZE).in(caseIds));
			cq2.select(cb.count(contact2.get(Contact.ID)));

			List<Long> caseContactCounts = em.createQuery(cq2).getResultList();

			counts[0] = caseContactCounts.stream().min((l1, l2) -> l1.compareTo(l2)).orElse(0L).intValue();
			counts[1] = caseContactCounts.stream().max((l1, l2) -> l1.compareTo(l2)).orElse(0L).intValue();
			counts[2] =  caseContactCounts.stream().reduce(0L, (a, b) -> a + b).intValue() / caseIds.size();

			return counts;
		}
	}

	@Override
	public int getNonSourceCaseCountForDashboard(List<String> caseUuids) {

		if (CollectionUtils.isEmpty(caseUuids)) {
			// Avoid empty IN clause
			return 0;
		}

		Query query = em.createNativeQuery(
			String.format(
				"SELECT DISTINCT count(case1_.id) FROM contact AS contact0_ LEFT OUTER JOIN cases AS case1_ ON (contact0_.%s_id = case1_.id) WHERE case1_.%s IN (%s)",
				Contact.RESULTING_CASE.toLowerCase(),
				Case.UUID,
				QueryHelper.concatStrings(caseUuids)));

		BigInteger count = (BigInteger) query.getSingleResult();
		return count.intValue();
	}

	public Contact fromDto(@NotNull ContactDto source) {
		Contact target = contactService.getByUuid(source.getUuid());
		if (target == null) {
			target = new Contact();
			target.setUuid(source.getUuid());
			if (source.getCreationDate() != null) {
				target.setCreationDate(new Timestamp(source.getCreationDate().getTime()));
			}
		}
		DtoHelper.validateDto(source, target);

		target.setCaze(caseService.getByReferenceDto(source.getCaze()));
		target.setPerson(personService.getByReferenceDto(source.getPerson()));
		target.setDisease(source.getDisease());
		target.setDiseaseDetails(source.getDiseaseDetails());

		target.setReportingUser(userService.getByReferenceDto(source.getReportingUser()));
		if (source.getReportDateTime() != null) {
			target.setReportDateTime(source.getReportDateTime());
		} else { // make sure we do have a report date
			target.setReportDateTime(new Date());
		}

		// use only date, not time
		target.setLastContactDate(source.getLastContactDate() != null ?
				DateHelper8.toDate(DateHelper8.toLocalDate(source.getLastContactDate())) : null);

		target.setContactProximity(source.getContactProximity());
		target.setContactClassification(source.getContactClassification());
		target.setContactStatus(source.getContactStatus());
		target.setFollowUpStatus(source.getFollowUpStatus());
		target.setFollowUpComment(source.getFollowUpComment());
		target.setFollowUpUntil(source.getFollowUpUntil());
		target.setOverwriteFollowUpUntil(source.isOverwriteFollowUpUntil());
		target.setContactOfficer(userService.getByReferenceDto(source.getContactOfficer()));
		target.setDescription(source.getDescription());
		target.setRelationToCase(source.getRelationToCase());
		target.setRelationDescription(source.getRelationDescription());
		target.setResultingCase(caseService.getByReferenceDto(source.getResultingCase()));

		target.setReportLat(source.getReportLat());
		target.setReportLon(source.getReportLon());
		target.setReportLatLonAccuracy(source.getReportLatLonAccuracy());
		target.setExternalID(source.getExternalID());

		target.setRegion(regionService.getByReferenceDto(source.getRegion()));
		target.setDistrict(districtService.getByReferenceDto(source.getDistrict()));

		target.setHighPriority(source.isHighPriority());
		target.setImmunosuppressiveTherapyBasicDisease(source.getImmunosuppressiveTherapyBasicDisease());
		target.setImmunosuppressiveTherapyBasicDiseaseDetails(source.getImmunosuppressiveTherapyBasicDiseaseDetails());
		target.setCareForPeopleOver60(source.getCareForPeopleOver60());

		target.setQuarantine(source.getQuarantine());
		target.setQuarantineFrom(source.getQuarantineFrom());
		target.setQuarantineTo(source.getQuarantineTo());

		target.setCaseIdExternalSystem(source.getCaseIdExternalSystem());
		target.setCaseOrEventInformation(source.getCaseOrEventInformation());

		target.setContactProximityDetails(source.getContactProximityDetails());
		target.setContactCategory(source.getContactCategory());

		target.setQuarantineHelpNeeded(source.getQuarantineHelpNeeded());
		target.setQuarantineOrderedVerbally(source.isQuarantineOrderedVerbally());
		target.setQuarantineOrderedOfficialDocument(source.isQuarantineOrderedOfficialDocument());
		target.setQuarantineOrderedVerballyDate(source.getQuarantineOrderedVerballyDate());
		target.setQuarantineOrderedOfficialDocumentDate(source.getQuarantineOrderedOfficialDocumentDate());
		target.setQuarantineHomePossible(source.getQuarantineHomePossible());
		target.setQuarantineHomePossibleComment(source.getQuarantineHomePossibleComment());
		target.setQuarantineHomeSupplyEnsured(source.getQuarantineHomeSupplyEnsured());
		target.setQuarantineHomeSupplyEnsuredComment(source.getQuarantineHomeSupplyEnsuredComment());
		target.setAdditionalDetails(source.getAdditionalDetails());

		return target;
	}

	@Override
	public boolean isDeleted(String contactUuid) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Long> cq = cb.createQuery(Long.class);
		Root<Contact> from = cq.from(Contact.class);

		cq.where(cb.and(
				cb.isTrue(from.get(Contact.DELETED)),
				cb.equal(from.get(AbstractDomainObject.UUID), contactUuid)));
		cq.select(cb.count(from));
		long count = em.createQuery(cq).getSingleResult();
		return count > 0;
	}

	@Override
	public List<DashboardContactDto> getContactsForDashboard(RegionReferenceDto regionRef,
			DistrictReferenceDto districtRef, Disease disease,
			Date from, Date to) {
		Region region = regionService.getByReferenceDto(regionRef);
		District district = districtService.getByReferenceDto(districtRef);
		User user = userService.getCurrentUser();

		return contactService.getContactsForDashboard(region, district, disease, from, to, user);
	}

	@Override
	public Map<ContactStatus, Long> getNewContactCountPerStatus(ContactCriteria contactCriteria) {
		User user = userService.getCurrentUser();

		return contactService.getNewContactCountPerStatus(contactCriteria, user);
	}

	@Override
	public Map<ContactClassification, Long> getNewContactCountPerClassification(ContactCriteria contactCriteria) {
		User user = userService.getCurrentUser();

		return contactService.getNewContactCountPerClassification(contactCriteria, user);
	}

	@Override
	public Map<FollowUpStatus, Long> getNewContactCountPerFollowUpStatus(ContactCriteria contactCriteria) {
		User user = userService.getCurrentUser();

		return contactService.getNewContactCountPerFollowUpStatus(contactCriteria, user);
	}

	@Override
	public int getFollowUpUntilCount(ContactCriteria contactCriteria) {
		User user = userService.getCurrentUser();

		return contactService.getFollowUpUntilCount(contactCriteria, user);
	}

	public static ContactReferenceDto toReferenceDto(Contact source) {
		if (source == null) {
			return null;
		}
		ContactReferenceDto target = new ContactReferenceDto(source.getUuid(), source.toString());
		return target;
	}

	public static ContactDto toDto(Contact source) {
		if (source == null) {
			return null;
		}
		ContactDto target = new ContactDto();
		DtoHelper.fillDto(target, source);

		target.setCaze(CaseFacadeEjb.toReferenceDto(source.getCaze()));
		target.setDisease(source.getDisease());
		target.setDiseaseDetails(source.getDiseaseDetails());
		target.setPerson(PersonFacadeEjb.toReferenceDto(source.getPerson()));

		target.setReportingUser(UserFacadeEjb.toReferenceDto(source.getReportingUser()));
		target.setReportDateTime(source.getReportDateTime());

		target.setLastContactDate(source.getLastContactDate());
		target.setContactProximity(source.getContactProximity());
		target.setContactClassification(source.getContactClassification());
		target.setContactStatus(source.getContactStatus());
		target.setFollowUpStatus(source.getFollowUpStatus());
		target.setFollowUpComment(source.getFollowUpComment());
		target.setFollowUpUntil(source.getFollowUpUntil());
		target.setOverwriteFollowUpUntil(source.isOverwriteFollowUpUntil());
		target.setContactOfficer(UserFacadeEjb.toReferenceDto(source.getContactOfficer()));
		target.setDescription(source.getDescription());
		target.setRelationToCase(source.getRelationToCase());
		target.setRelationDescription(source.getRelationDescription());
		target.setResultingCase(CaseFacadeEjb.toReferenceDto(source.getResultingCase()));

		target.setReportLat(source.getReportLat());
		target.setReportLon(source.getReportLon());
		target.setReportLatLonAccuracy(source.getReportLatLonAccuracy());
		target.setExternalID(source.getExternalID());

		target.setRegion(RegionFacadeEjb.toReferenceDto(source.getRegion()));
		target.setDistrict(DistrictFacadeEjb.toReferenceDto(source.getDistrict()));

		target.setHighPriority(source.isHighPriority());
		target.setImmunosuppressiveTherapyBasicDisease(source.getImmunosuppressiveTherapyBasicDisease());
		target.setImmunosuppressiveTherapyBasicDiseaseDetails(source.getImmunosuppressiveTherapyBasicDiseaseDetails());
		target.setCareForPeopleOver60(source.getCareForPeopleOver60());

		target.setQuarantine(source.getQuarantine());
		target.setQuarantineFrom(source.getQuarantineFrom());
		target.setQuarantineTo(source.getQuarantineTo());

		target.setCaseIdExternalSystem(source.getCaseIdExternalSystem());
		target.setCaseOrEventInformation(source.getCaseOrEventInformation());

		target.setContactProximityDetails(source.getContactProximityDetails());
		target.setContactCategory(source.getContactCategory());

		target.setQuarantineHelpNeeded(source.getQuarantineHelpNeeded());
		target.setQuarantineOrderedVerbally(source.isQuarantineOrderedVerbally());
		target.setQuarantineOrderedOfficialDocument(source.isQuarantineOrderedOfficialDocument());
		target.setQuarantineOrderedVerballyDate(source.getQuarantineOrderedVerballyDate());
		target.setQuarantineOrderedOfficialDocumentDate(source.getQuarantineOrderedOfficialDocumentDate());
		target.setQuarantineHomePossible(source.getQuarantineHomePossible());
		target.setQuarantineHomePossibleComment(source.getQuarantineHomePossibleComment());
		target.setQuarantineHomeSupplyEnsured(source.getQuarantineHomeSupplyEnsured());
		target.setQuarantineHomeSupplyEnsuredComment(source.getQuarantineHomeSupplyEnsuredComment());
		target.setAdditionalDetails(source.getAdditionalDetails());

		return target;
	}

	@RolesAllowed(UserRole._SYSTEM)
	@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
	public void generateContactFollowUpTasks() {
		// get all contacts that are followed up
		LocalDateTime fromDateTime = LocalDate.now().atStartOfDay();
		LocalDateTime toDateTime = fromDateTime.plusDays(1);
		List<Contact> contacts = contactService.getFollowUpBetween(DateHelper8.toDate(fromDateTime),
				DateHelper8.toDate(toDateTime));

		for (Contact contact : contacts) {
			// Only generate tasks for contacts that are under follow-up
			if (!(contact.getFollowUpStatus().equals(FollowUpStatus.FOLLOW_UP) || contact.getFollowUpStatus().equals(FollowUpStatus.LOST))) {
				continue;
			}

			User assignee = null;
			if (contact.getContactOfficer() != null) {
				// 1) The contact officer that is responsible for the contact
				assignee = contact.getContactOfficer();
			} else {
				// 2) A random contact officer from the contact's, contact person's or contact case's district
				List<User> officers = new ArrayList<>();
				if (contact.getDistrict() != null) {
					officers = userService.getAllByDistrict(contact.getDistrict(), false, UserRole.CONTACT_OFFICER);
				}
				if (officers.isEmpty() && contact.getPerson().getAddress().getDistrict() != null) {
					officers = userService.getAllByDistrict(contact.getPerson().getAddress().getDistrict(), false, UserRole.CONTACT_OFFICER);
				}
				if (officers.isEmpty() && contact.getCaze() != null && contact.getCaze().getDistrict() != null) {
					officers = userService.getAllByDistrict(contact.getCaze().getDistrict(), false, UserRole.CONTACT_OFFICER);
				}
				if (!officers.isEmpty()) {
					Random rand = new Random();
					assignee = officers.get(rand.nextInt(officers.size()));
				}
			}

			if (assignee == null) {
				// 3) Assign a random contact supervisor from the contact's, contact person's or contact case's region
				List<User> supervisors = new ArrayList<>();
				if (contact.getRegion() != null) {
					supervisors = userService.getAllByRegionAndUserRoles(contact.getRegion(), UserRole.CONTACT_SUPERVISOR);
				}
				if (supervisors.isEmpty() && contact.getPerson().getAddress().getRegion() != null) {
					supervisors = userService.getAllByRegionAndUserRoles(contact.getPerson().getAddress().getRegion(), UserRole.CONTACT_SUPERVISOR);
				}
				if (supervisors.isEmpty()) {
					supervisors = userService.getAllByRegionAndUserRoles(contact.getCaze().getRegion(), UserRole.CONTACT_SUPERVISOR);
				}
				if (!supervisors.isEmpty()) {
					Random rand = new Random();
					assignee = supervisors.get(rand.nextInt(supervisors.size()));
				} else {
					logger.warn("Contact has not contact officer and no region - can't create follow-up task: " + contact.getUuid());
					continue;
				}
			}

			// find already existing tasks
			TaskCriteria pendingUserTaskCriteria = new TaskCriteria()
					.contact(contact.toReference())
					.taskType(TaskType.CONTACT_FOLLOW_UP)
					.assigneeUser(assignee.toReference())
					.taskStatus(TaskStatus.PENDING);
			List<Task> pendingUserTasks = taskService.findBy(pendingUserTaskCriteria);

			if (!pendingUserTasks.isEmpty()) {
				// the user still has a pending task for this contact
				continue;
			}

			TaskCriteria dayTaskCriteria = new TaskCriteria()
					.contact(contact.toReference())
					.taskType(TaskType.CONTACT_FOLLOW_UP)
					.dueDateBetween(DateHelper8.toDate(fromDateTime), DateHelper8.toDate(toDateTime));
			List<Task> dayTasks = taskService.findBy(dayTaskCriteria);

			if (!dayTasks.isEmpty()) {
				// there is already a task for the exact day
				continue;
			}

			// none found -> create the task
			Task task = taskService.buildTask(null);
			task.setTaskContext(TaskContext.CONTACT);
			task.setContact(contact);
			task.setTaskType(TaskType.CONTACT_FOLLOW_UP);
			task.setSuggestedStart(DateHelper8.toDate(fromDateTime));
			task.setDueDate(DateHelper8.toDate(toDateTime.minusMinutes(1)));
			task.setAssigneeUser(assignee);

			if (contact.isHighPriority()) {
				task.setPriority(TaskPriority.HIGH);
			}

			taskService.ensurePersisted(task);
		}
	}

	@Override
	public void validate(ContactDto contact) throws ValidationRuntimeException {
		// Check whether any required field that does not have a not null constraint in the database is empty
		if (contact.getReportDateTime() == null) {
			throw new ValidationRuntimeException(I18nProperties.getValidationError(Validations.validReportDateTime));
		}
		if (contact.getReportingUser() == null) {
			throw new ValidationRuntimeException(I18nProperties.getValidationError(Validations.validReportingUser));
		}
		if (contact.getDisease() == null) {
			throw new ValidationRuntimeException(I18nProperties.getValidationError(Validations.validDisease));
		}
		if (contact.getPerson() == null) {
			throw new ValidationRuntimeException(I18nProperties.getValidationError(Validations.validPerson));
		}
		if (contact.isOverwriteFollowUpUntil() && contact.getFollowUpUntil() == null) {
			throw new ValidationRuntimeException(I18nProperties.getValidationError(Validations.emptyOverwrittenFollowUpUntilDate));
		}
		if (contact.getCaze() == null && (contact.getRegion() == null || contact.getDistrict() == null)) {
			throw new ValidationRuntimeException(I18nProperties.getValidationError(Validations.contactWithoutInfrastructureData));
		}
	}

	@Override
	public List<SimilarContactDto> getMatchingContacts(ContactSimilarityCriteria criteria) {
		final CriteriaBuilder cb = em.getCriteriaBuilder();
		final CriteriaQuery<SimilarContactDto> cq = cb.createQuery(SimilarContactDto.class);
		final Root<Contact> contactRoot = cq.from(Contact.class);
		final Join<Contact, Person> personJoin = contactRoot.join(Contact.PERSON, JoinType.LEFT);
		final Join<Contact, Case> caseJoin = contactRoot.join(Contact.CAZE, JoinType.LEFT);
		final Join<Case, Person> casePersonJoin = contactRoot.join(Case.PERSON, JoinType.LEFT);

		cq.multiselect(personJoin.get(Person.FIRST_NAME), personJoin.get(Person.LAST_NAME),
				contactRoot.get(Contact.UUID), caseJoin.get(Case.UUID), casePersonJoin.get(Person.FIRST_NAME), casePersonJoin.get(Person.LAST_NAME),
				contactRoot.get(Contact.CASE_ID_EXTERNAL_SYSTEM), contactRoot.get(Contact.LAST_CONTACT_DATE),
				contactRoot.get(Contact.CONTACT_PROXIMITY), contactRoot.get(Contact.CONTACT_CLASSIFICATION),
				contactRoot.get(Contact.CONTACT_STATUS), contactRoot.get(Contact.FOLLOW_UP_STATUS));

		final Predicate defaultFilter = contactService.createDefaultFilter(cb, contactRoot);
		final Predicate userFilter = contactService.createUserFilter(cb, cq, contactRoot);

		final PersonReferenceDto person = criteria.getPerson();
		final Predicate samePersonFilter = person != null ? cb.equal(personJoin.get(Person.UUID), person.getUuid()) : null;

		final Disease disease = criteria.getDisease();
		final Predicate diseaseFilter = disease != null ? cb.equal(contactRoot.get(Contact.DISEASE), disease) : null;

		final CaseReferenceDto caze = criteria.getCaze();
		final Predicate cazeFilter = caze != null ? cb.equal(caseJoin.get(Case.UUID), caze.getUuid()) : null;

		final Date reportDate = criteria.getReportDate();
		final Date lastContactDate = criteria.getLastContactDate();
		final Predicate recentContactsFilter = AbstractAdoService.and(cb,
				contactService.recentDateFilter(cb, reportDate, contactRoot.get(Contact.REPORT_DATE_TIME), 30),
				contactService.recentDateFilter(cb, lastContactDate, contactRoot.get(Contact.LAST_CONTACT_DATE), 30));

		cq.where(AbstractAdoService.and(cb, defaultFilter, userFilter, samePersonFilter, diseaseFilter, cazeFilter,
				recentContactsFilter));

		return em.createQuery(cq).getResultList();
	}

	@LocalBean
	@Stateless
	public static class ContactFacadeEjbLocal extends ContactFacadeEjb {

	}

	@Override
	public boolean isContactEditAllowed(String contactUuid) {		
		Contact contact = contactService.getByUuid(contactUuid);
		return contactEditAuthorization.isContactEditAllowed(contact);
	}

}
