from flask import Flask, request, jsonify

app = Flask(__name__)

def linkmp(payload):
    import mercadopago

    # Reemplaza "PROD_ACCESS_TOKEN" con tu token de acceso de producción válido
    sdk = mercadopago.SDK("APP_USR-3459389918051534-091015-80daccb18b71d10da2acb3764a01b726__LD_LB__-192113402")

    # Mapear campos del payload a los requeridos para la preferencia
    preference_data = {
        "items": [
            {
                "title": payload.get("reference"),  # Mapear 'reference' a 'title'
                "quantity": 1,
                "unit_price": payload.get("totalAmount"),  # Mapear 'totalAmount' a 'unit_price'
            }
        ]
    }

    # Crear la preferencia utilizando el SDK de Mercado Pago
    preference_response = sdk.preference().create(preference_data)
    preference = preference_response["response"]

    # Obtener el enlace de pago utilizando el ID de la preferencia
    payment_link = preference["init_point"]
    return payment_link

@app.route('/generar_link', methods=['POST'])
def generar_link():
    payload = request.get_json()

    if payload is None:
        return jsonify({'error': 'No se proporcionó un JJSON válido'}), 400

    payment_link = linkmp(payload)
    return jsonify({'enlace_de_pago': payment_link})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=True)
