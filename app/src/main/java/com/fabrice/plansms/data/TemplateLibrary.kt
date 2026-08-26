package com.fabrice.plansms.data

/**
 * Bibliothèque de modèles types, prêts à ajouter en un geste.
 * Variables disponibles : {{prenom}} {{nom}} {{date}} {{heure}}
 */
object TemplateLibrary {

    data class Suggestion(val category: String, val name: String, val body: String)

    private const val SIGNATURE = "Cordialement, Cabinet Fabrice Heuvrard."

    val all: List<Suggestion> = listOf(
        // --- Appels ---
        Suggestion(
            "Appels", "Motif de l'appel",
            "Bonjour, pouvez-vous svp m'indiquer le motif de votre appel ? $SIGNATURE"
        ),
        Suggestion(
            "Appels", "Appel manqué de ma part",
            "Bonjour {{prenom}}, j'ai tenté de vous joindre sans succès. " +
                "Pouvez-vous me rappeler ou m'indiquer un créneau qui vous convient ? $SIGNATURE"
        ),
        Suggestion(
            "Appels", "Réponse à un appel manqué",
            "Bonjour {{prenom}}, j'ai bien vu votre appel. Je reviens vers vous dans la journée. $SIGNATURE"
        ),
        Suggestion(
            "Appels", "Message vocal reçu",
            "Bonjour {{prenom}}, j'ai bien reçu votre message vocal. Je vous rappelle avant {{heure}}. $SIGNATURE"
        ),
        Suggestion(
            "Appels", "Indisponible aujourd'hui",
            "Bonjour {{prenom}}, je suis en mission aujourd'hui et difficilement joignable. " +
                "Écrivez-moi par SMS ou par email, je vous réponds dès que possible. $SIGNATURE"
        ),

        // --- Rendez-vous ---
        Suggestion(
            "Rendez-vous", "Confirmation de RDV",
            "Bonjour {{prenom}}, je vous confirme notre rendez-vous du {{date}} à {{heure}}. $SIGNATURE"
        ),
        Suggestion(
            "Rendez-vous", "Rappel la veille",
            "Bonjour, nous avons rendez-vous demain à {{heure}}. " +
                "En cas d'indisponibilité de votre part, merci de me prévenir. $SIGNATURE"
        ),
        Suggestion(
            "Rendez-vous", "Proposition de créneau",
            "Bonjour {{prenom}}, seriez-vous disponible le {{date}} à {{heure}} ? " +
                "Sinon, indiquez-moi vos disponibilités. $SIGNATURE"
        ),
        Suggestion(
            "Rendez-vous", "Report de RDV",
            "Bonjour {{prenom}}, un imprévu m'oblige à décaler notre rendez-vous du {{date}}. " +
                "Je vous propose de le reporter — quelles sont vos disponibilités ? $SIGNATURE"
        ),
        Suggestion(
            "Rendez-vous", "Annulation de RDV",
            "Bonjour {{prenom}}, je dois annuler notre rendez-vous du {{date}} à {{heure}}. " +
                "Je reviens vers vous rapidement pour convenir d'une nouvelle date. $SIGNATURE"
        ),
        Suggestion(
            "Rendez-vous", "Lien visioconférence",
            "Bonjour {{prenom}}, notre visio est confirmée le {{date}} à {{heure}}. " +
                "Je vous envoie le lien de connexion par email. $SIGNATURE"
        ),
        Suggestion(
            "Rendez-vous", "Retard",
            "Bonjour {{prenom}}, je serai en retard d'une quinzaine de minutes à notre rendez-vous. " +
                "Merci de votre compréhension. $SIGNATURE"
        ),

        // --- Documents & mission ---
        Suggestion(
            "Documents", "Demande de documents",
            "Bonjour {{prenom}}, pour avancer sur votre dossier, pourriez-vous me transmettre " +
                "les pièces demandées par email avant le {{date}} ? $SIGNATURE"
        ),
        Suggestion(
            "Documents", "Relance documents (1re)",
            "Bonjour {{prenom}}, je reviens vers vous concernant les documents attendus. " +
                "Pouvez-vous me les faire parvenir cette semaine ? $SIGNATURE"
        ),
        Suggestion(
            "Documents", "Relance documents (ferme)",
            "Bonjour {{prenom}}, sans les pièces demandées, je ne peux pas poursuivre mes travaux " +
                "dans les délais prévus. Merci de me les transmettre avant le {{date}}. $SIGNATURE"
        ),
        Suggestion(
            "Documents", "Accusé de réception",
            "Bonjour {{prenom}}, j'accuse réception de vos documents. Je reviens vers vous " +
                "après examen. $SIGNATURE"
        ),
        Suggestion(
            "Documents", "Demande d'email",
            "Bonjour {{prenom}}, pouvez-vous svp m'envoyer un email détaillant votre demande ? " +
                "Cela me permettra de vous répondre précisément. $SIGNATURE"
        ),
        Suggestion(
            "Documents", "Signature attendue",
            "Bonjour {{prenom}}, il ne manque que votre signature pour finaliser le dossier. " +
                "Pouvez-vous la retourner avant le {{date}} ? $SIGNATURE"
        ),

        // --- Facturation ---
        Suggestion(
            "Facturation", "Échéance à venir",
            "Bonjour {{prenom}}, votre facture arrive à échéance le {{date}}. " +
                "Merci d'anticiper votre règlement. $SIGNATURE"
        ),
        Suggestion(
            "Facturation", "Relance impayé",
            "Bonjour {{prenom}}, sauf erreur de ma part, votre facture échue le {{date}} " +
                "reste impayée. Pouvez-vous régulariser ou me tenir informé ? $SIGNATURE"
        ),
        Suggestion(
            "Facturation", "Remerciement règlement",
            "Bonjour {{prenom}}, j'ai bien reçu votre règlement. Je vous remercie. $SIGNATURE"
        ),

        // --- Cabinet ---
        Suggestion(
            "Cabinet", "Convocation assemblée",
            "Bonjour {{prenom}}, l'assemblée générale est fixée au {{date}} à {{heure}}. " +
                "Merci de me confirmer votre présence. $SIGNATURE"
        ),
        Suggestion(
            "Cabinet", "Fermeture congés",
            "Bonjour, le cabinet est fermé jusqu'au {{date}}. Pour toute urgence, " +
                "merci de passer par email. $SIGNATURE"
        ),
        Suggestion(
            "Cabinet", "Fin de mission",
            "Bonjour {{prenom}}, mes travaux sont terminés et le rapport vous a été adressé. " +
                "Je reste à votre disposition. $SIGNATURE"
        )
    )

    val categories: List<String> get() = all.map { it.category }.distinct()
}
