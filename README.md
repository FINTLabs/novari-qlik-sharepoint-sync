# qlik-to-sharepoint-sync

Imports users from Qlik Cloud and gets their groups. Finds the relation to county number.
Import guests from Microsoft Tenant and gets groups with the 
members defined in an environment list. 
Adds Qlik Cloud users as guests in Entra, and matches the guests with the
users from Qlik Cloud and adds them to the correct groups in Entra. Removes wrong assignments.

### Environment variables required
- excluded-email-domains=domain1.com,domain2.com
- group-mappings=group1inTenant,group2inTenant,group3inTenant
- qlik-api-token=<api token from Qlik>
- qlik-base-url=https://domain.eu.qlikcloud.com
- client-secret=<client secret from app registration in Entra>
- client-id=<client id from app registration in Entra>
- tenant-id=<tenant id from app registration in Entra>
- invite-redirect-url=https://domain.sharepoint.com/sites/MySharepointSite
